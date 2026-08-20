param(
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [Parameter(Mandatory = $true)][string]$OutputJson,
    [Parameter(Mandatory = $true)][string]$WorkDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null

$script:asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and
        $_.GetGenericArguments().Count -eq 1 -and $_.GetParameters().Count -eq 1
    } | Select-Object -First 1

function Await-WinRt {
    param($Operation, [Type]$ResultType)
    $task = $script:asTask.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
    $task.Wait()
    return $task.Result
}

function Invoke-OcrPass {
    param(
        [string]$InputPath,
        [double]$Scale,
        [int]$OriginalWidth,
        [int]$OriginalHeight,
        [string]$TemporaryPath,
        $Engine
    )

    $ocrPath = $InputPath
    if ([Math]::Abs($Scale - 1.0) -gt 0.015) {
        $source = [System.Drawing.Image]::FromFile($InputPath)
        try {
            $width = [Math]::Max(1, [int][Math]::Round($OriginalWidth * $Scale))
            $height = [Math]::Max(1, [int][Math]::Round($OriginalHeight * $Scale))
            $bitmap = New-Object System.Drawing.Bitmap $width, $height
            try {
                $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                try {
                    $graphics.Clear([System.Drawing.Color]::White)
                    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                    $graphics.DrawImage($source, 0, 0, $width, $height)
                } finally {
                    $graphics.Dispose()
                }
                $bitmap.Save($TemporaryPath, [System.Drawing.Imaging.ImageFormat]::Png)
            } finally {
                $bitmap.Dispose()
            }
        } finally {
            $source.Dispose()
        }
        $ocrPath = $TemporaryPath
    }

    $file = Await-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ocrPath)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Await-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $softwareBitmap = Await-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Await-WinRt ($Engine.RecognizeAsync($softwareBitmap)) ([Windows.Media.Ocr.OcrResult])
        $lines = @()
        foreach ($line in $result.Lines) {
            $words = @($line.Words)
            if ($words.Count -eq 0) { continue }
            $left = ($words | ForEach-Object { $_.BoundingRect.X } | Measure-Object -Minimum).Minimum / $Scale
            $top = ($words | ForEach-Object { $_.BoundingRect.Y } | Measure-Object -Minimum).Minimum / $Scale
            $right = ($words | ForEach-Object { $_.BoundingRect.X + $_.BoundingRect.Width } | Measure-Object -Maximum).Maximum / $Scale
            $bottom = ($words | ForEach-Object { $_.BoundingRect.Y + $_.BoundingRect.Height } | Measure-Object -Maximum).Maximum / $Scale
            $text = (($words | ForEach-Object { $_.Text }) -join '')
            if (-not [string]::IsNullOrWhiteSpace($text)) {
                $lines += [ordered]@{
                    text = $text
                    left = [Math]::Max(0.0, [double]$left)
                    top = [Math]::Max(0.0, [double]$top)
                    right = [Math]::Min([double]$OriginalWidth, [double]$right)
                    bottom = [Math]::Min([double]$OriginalHeight, [double]$bottom)
                }
            }
        }
        return [ordered]@{ scale = $Scale; lines = $lines }
    } finally {
        if ($stream -is [System.IDisposable]) { $stream.Dispose() }
        if ($ocrPath -ne $InputPath -and (Test-Path -LiteralPath $ocrPath)) {
            Remove-Item -LiteralPath $ocrPath -Force
        }
    }
}

$sourceRoot = (Resolve-Path -LiteralPath $SourceDirectory).Path
New-Item -ItemType Directory -Path $WorkDirectory -Force | Out-Null
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('zh-Hans-CN'))
if ($null -eq $engine) { throw 'Windows zh-Hans OCR engine is not installed.' }

$files = @(Get-ChildItem -LiteralPath $sourceRoot -File | Where-Object { $_.Extension -match '^\.(png|jpg|jpeg)$' } | Sort-Object Name)
$records = @()
for ($index = 0; $index -lt $files.Count; $index++) {
    $file = $files[$index]
    try {
        $image = [System.Drawing.Image]::FromFile($file.FullName)
        try { $width = $image.Width; $height = $image.Height } finally { $image.Dispose() }
        $longSide = [Math]::Max($width, $height)
        $targetSides = @(960.0, 1500.0)
        $passes = @()
        for ($passIndex = 0; $passIndex -lt $targetSides.Count; $passIndex++) {
            $scale = ($targetSides[$passIndex] / [Math]::Max(1.0, $longSide))
            $scale = [Math]::Min(10.0, [Math]::Max(0.20, $scale))
            $temporary = Join-Path $WorkDirectory ("ocr-{0:D4}-{1}.png" -f $index, $passIndex)
            $passes += Invoke-OcrPass -InputPath $file.FullName -Scale $scale -OriginalWidth $width -OriginalHeight $height -TemporaryPath $temporary -Engine $engine
        }
        $records += [ordered]@{
            source = $file.Name
            width = $width
            height = $height
            passes = $passes
            error = ''
        }
    } catch {
        $records += [ordered]@{
            source = $file.Name
            width = 0
            height = 0
            passes = @()
            error = $_.Exception.Message
        }
    }
    Write-Output ("[{0:D3}/{1:D3}] {2}" -f ($index + 1), $files.Count, $file.Name)
}

$parent = Split-Path -Parent $OutputJson
if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
$records | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputJson -Encoding utf8
Write-Output ("OCR_COMPLETE files={0} output={1}" -f $records.Count, $OutputJson)
