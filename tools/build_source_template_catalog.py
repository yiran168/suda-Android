"""Build a fully local, editable Android template catalog from source artwork.

The script intentionally depends only on Pillow/NumPy from the workstation. OCR is supplied by
the checked-in Windows OCR extraction script and barcode payloads by the checked-in ZXing helper.
Neither OCR engines nor image-processing libraries are packaged in the Android application.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Iterable

import numpy as np
from PIL import Image


DOTS_PER_MM = 203.0 / 25.4
MIN_WIDTH_MM = 10.0
MAX_SOURCE_WIDTH_MM = 55.0
PUNCTUATION = " \t\r\n,，.。:：;；/\\|·•-—_()（）[]【】<>《》'\"!?！？+*×"


KOTLIN_HEADER = """package com.qrint.studio.data

internal data class SourceTextSpec(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val emphasis: Boolean,
    val alignment: String,
    val crossChecked: Boolean,
)

internal data class SourceCodeSpec(
    val type: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val content: String,
)

internal data class SourceShapeSpec(
    val kind: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val strokeWidth: Float,
)

internal data class SourceTemplateSpec(
    val id: String,
    val title: String,
    val category: String,
    val widthMm: Float,
    val heightMm: Float,
    val decorResource: String,
    val text: List<SourceTextSpec>,
    val codes: List<SourceCodeSpec>,
    val shapes: List<SourceShapeSpec>,
)

"""


@dataclass(frozen=True)
class Box:
    left: float
    top: float
    right: float
    bottom: float

    @property
    def width(self) -> float:
        return max(0.0, self.right - self.left)

    @property
    def height(self) -> float:
        return max(0.0, self.bottom - self.top)

    @property
    def area(self) -> float:
        return self.width * self.height

    @property
    def center(self) -> tuple[float, float]:
        return ((self.left + self.right) / 2.0, (self.top + self.bottom) / 2.0)

    def padded(self, amount: float, width: int, height: int) -> "Box":
        return Box(
            max(0.0, self.left - amount),
            max(0.0, self.top - amount),
            min(float(width), self.right + amount),
            min(float(height), self.bottom + amount),
        )


def intersection(first: Box, second: Box) -> float:
    width = max(0.0, min(first.right, second.right) - max(first.left, second.left))
    height = max(0.0, min(first.bottom, second.bottom) - max(first.top, second.top))
    return width * height


def iou(first: Box, second: Box) -> float:
    overlap = intersection(first, second)
    union = first.area + second.area - overlap
    return overlap / max(1.0, union)


def overlap_against_smaller(first: Box, second: Box) -> float:
    return intersection(first, second) / max(1.0, min(first.area, second.area))


def normalize_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).strip(PUNCTUATION)
    return "".join(character for character in normalized if not character.isspace() and character not in PUNCTUATION).upper()


def plausible_text(value: str) -> bool:
    value = value.strip()
    if not value or len(value) > 120:
        return False
    useful = sum(character.isalnum() or "\u3400" <= character <= "\u9fff" for character in value)
    if useful / max(1, len(value)) < 0.45:
        return False
    cjk = sum("\u3400" <= character <= "\u9fff" for character in value)
    latin = sum("A" <= character.upper() <= "Z" for character in value)
    # Mixed OCR noise such as T!MES霾匪Y is less trustworthy unless two scales agree.
    return not (cjk > 0 and latin > 4 and cjk / max(1, latin) < 0.45)


def clean_ocr_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).strip()
    value = re.sub(r"(?<=\d)[一—－](?=\d)", "-", value)
    value = re.sub(r"(?<=\d)[mM]\|(?=\b|[^A-Za-z])", "ml", value)
    return value


def merge_ocr(record: dict) -> list[dict]:
    passes = sorted(record.get("passes", []), key=lambda item: float(item.get("scale", 0.0)))
    if not passes:
        return []
    primary = passes[-1].get("lines", [])
    secondary = passes[-2].get("lines", []) if len(passes) > 1 else []
    candidates: list[dict] = []
    for line in primary:
        text = clean_ocr_text(str(line.get("text", "")))
        box = Box(float(line["left"]), float(line["top"]), float(line["right"]), float(line["bottom"]))
        if box.width < 2 or box.height < 2:
            continue
        best = None
        best_score = 0.0
        for other in secondary:
            other_box = Box(float(other["left"]), float(other["top"]), float(other["right"]), float(other["bottom"]))
            geometry = max(iou(box, other_box), overlap_against_smaller(box, other_box) * 0.82)
            if geometry < 0.24:
                continue
            similarity = SequenceMatcher(None, normalize_text(text), normalize_text(str(other.get("text", "")))).ratio()
            score = geometry * 0.45 + similarity * 0.55
            if score > best_score:
                best = other
                best_score = score
        cross_checked = best is not None and SequenceMatcher(
            None,
            normalize_text(text),
            normalize_text(str(best.get("text", ""))),
        ).ratio() >= 0.76
        if not plausible_text(text):
            continue
        candidates.append({"text": text, "box": box, "crossChecked": cross_checked})

    # Keep the larger line when OCR repeats a word inside a stylised headline.
    accepted: list[dict] = []
    for candidate in sorted(candidates, key=lambda item: item["box"].area, reverse=True):
        duplicate = any(
            overlap_against_smaller(candidate["box"], prior["box"]) > 0.83
            and (
                normalize_text(candidate["text"]) in normalize_text(prior["text"])
                or normalize_text(prior["text"]) in normalize_text(candidate["text"])
            )
            for prior in accepted
        )
        if not duplicate:
            accepted.append(candidate)
    return sorted(accepted, key=lambda item: (item["box"].top, item["box"].left))


def category_for(categories: list[dict], text: str) -> str:
    value = unicodedata.normalize("NFKC", text).upper()
    # Specific industries are ordered before general words such as 标签、日期 and 店.
    order = ["直播带货", "收款码", "医药行业", "餐饮服务", "通讯电力", "仓储物流", "生产制造", "办公管理", "居家生活", "商业零售", "通用"]
    by_name = {item["name"]: item for item in categories}
    for name in order:
        if any(str(keyword).upper() in value for keyword in by_name[name]["keywords"]):
            return name
    return "其他场景"


FORMAT_MAP = {
    "QR_CODE": "QR_CODE",
    "DATA_MATRIX": "DATA_MATRIX",
    "PDF_417": "PDF_417",
    "AZTEC": "AZTEC",
    "CODE_128": "CODE_128",
    "CODE_39": "CODE_39",
    "CODE_93": "CODE_93",
    "CODABAR": "CODABAR",
    "EAN_13": "EAN_13",
    "EAN_8": "EAN_8",
    "UPC_A": "UPC_A",
    "UPC_E": "UPC_E",
    "ITF": "ITF",
}


def decoded_codes(record: dict, width: int, height: int) -> list[dict]:
    values: list[dict] = []
    for raw in record.get("codes", []):
        code_type = FORMAT_MAP.get(str(raw.get("format", "")))
        points = raw.get("points", [])
        if code_type is None or len(points) < 2:
            continue
        x_values = [float(point[0]) for point in points]
        y_values = [float(point[1]) for point in points]
        left, right = min(x_values), max(x_values)
        top, bottom = min(y_values), max(y_values)
        span_x = max(3.0, right - left)
        span_y = max(3.0, bottom - top)
        if code_type in {"QR_CODE", "DATA_MATRIX", "AZTEC"}:
            if len(points) <= 3:
                pad = max(span_x, span_y) * 0.24
                left -= pad
                right += pad
                top -= pad
                bottom += pad
        elif code_type == "PDF_417":
            left -= span_x * 0.08
            right += span_x * 0.08
            top -= max(6.0, span_x * 0.12)
            bottom += max(6.0, span_x * 0.12)
        else:
            target_height = max(18.0, span_x * 0.34)
            center_y = (top + bottom) / 2.0
            top = center_y - target_height / 2.0
            bottom = center_y + target_height / 2.0
            left -= span_x * 0.04
            right += span_x * 0.04
        box = Box(max(0.0, left), max(0.0, top), min(float(width), right), min(float(height), bottom))
        if box.width < 4 or box.height < 4:
            continue
        try:
            content = base64.b64decode(str(raw.get("contentBase64", ""))).decode("utf-8").lstrip("\ufeff")
        except (ValueError, UnicodeDecodeError):
            continue
        if not content:
            continue
        values.append({"type": code_type, "content": content, "box": box})
    return values


def longest_true_run(values: np.ndarray) -> tuple[int, int]:
    padded = np.concatenate(([False], values.astype(bool), [False]))
    edges = np.flatnonzero(padded[1:] != padded[:-1])
    if len(edges) < 2:
        return 0, 0
    starts = edges[::2]
    ends = edges[1::2]
    index = int(np.argmax(ends - starts))
    return int(starts[index]), int(ends[index])


def detect_lines(image: np.ndarray, exclusions: list[Box]) -> list[dict]:
    gray = image[..., :3].astype(np.float32) @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    threshold = min(115.0, float(np.percentile(gray, 22)) + 24.0)
    dark = gray < threshold
    height, width = dark.shape
    candidates: list[dict] = []

    horizontal_rows: list[tuple[int, int, int]] = []
    for y in range(height):
        start, end = longest_true_run(dark[y])
        if end - start >= max(18, int(width * 0.28)):
            horizontal_rows.append((y, start, end))
    groups: list[list[tuple[int, int, int]]] = []
    for row in horizontal_rows:
        if not groups or row[0] > groups[-1][-1][0] + 1:
            groups.append([row])
        else:
            groups[-1].append(row)
    for group in groups:
        thickness = group[-1][0] - group[0][0] + 1
        if thickness > max(8, int(height * 0.025)):
            continue
        box = Box(float(min(row[1] for row in group)), float(group[0][0]), float(max(row[2] for row in group)), float(group[-1][0] + 1))
        if not any(overlap_against_smaller(box, exclusion) > 0.20 for exclusion in exclusions):
            candidates.append({"kind": "LINE", "box": box, "stroke": float(max(1, thickness))})

    vertical_columns: list[tuple[int, int, int]] = []
    for x in range(width):
        start, end = longest_true_run(dark[:, x])
        if end - start >= max(18, int(height * 0.28)):
            vertical_columns.append((x, start, end))
    groups = []
    for column in vertical_columns:
        if not groups or column[0] > groups[-1][-1][0] + 1:
            groups.append([column])
        else:
            groups[-1].append(column)
    for group in groups:
        thickness = group[-1][0] - group[0][0] + 1
        if thickness > max(8, int(width * 0.025)):
            continue
        box = Box(float(group[0][0]), float(min(column[1] for column in group)), float(group[-1][0] + 1), float(max(column[2] for column in group)))
        if not any(overlap_against_smaller(box, exclusion) > 0.20 for exclusion in exclusions):
            candidates.append({"kind": "VERTICAL_LINE", "box": box, "stroke": float(max(1, thickness))})
    return candidates[:24]


def fill_region(array: np.ndarray, box: Box) -> None:
    height, width = array.shape[:2]
    left = max(0, int(math.floor(box.left)))
    top = max(0, int(math.floor(box.top)))
    right = min(width, int(math.ceil(box.right)))
    bottom = min(height, int(math.ceil(box.bottom)))
    if right <= left or bottom <= top:
        return
    ring = max(2, min(12, int(min(right - left, bottom - top) * 0.12)))
    outer_left, outer_top = max(0, left - ring), max(0, top - ring)
    outer_right, outer_bottom = min(width, right + ring), min(height, bottom + ring)
    samples = np.concatenate(
        [
            array[outer_top:top, outer_left:outer_right].reshape(-1, 3),
            array[bottom:outer_bottom, outer_left:outer_right].reshape(-1, 3),
            array[top:bottom, outer_left:left].reshape(-1, 3),
            array[top:bottom, right:outer_right].reshape(-1, 3),
        ],
        axis=0,
    )
    color = np.median(samples, axis=0).astype(np.uint8) if len(samples) else np.array([255, 255, 255], dtype=np.uint8)
    array[top:bottom, left:right] = color


def prepare_decor(image: Image.Image, text: list[dict], codes: list[dict], shapes: list[dict]) -> tuple[Image.Image, bool]:
    array = np.asarray(image.convert("RGB"), dtype=np.uint8).copy()
    width, height = image.size
    regions = [item["box"].padded(max(2.0, item["box"].height * 0.10), width, height) for item in text]
    regions += [item["box"].padded(max(2.0, min(item["box"].width, item["box"].height) * 0.05), width, height) for item in codes]
    regions += [item["box"].padded(max(1.0, item["stroke"]), width, height) for item in shapes]
    for region in regions:
        fill_region(array, region)
    mean = float(array.mean())
    standard_deviation = float(array.std())
    dark_fraction = float(np.mean(np.mean(array, axis=2) < 225))
    has_decor = mean < 248.0 or standard_deviation > 3.2 or dark_fraction > 0.004
    return Image.fromarray(array), has_decor


def normalized_box(box: Box, width: int, height: int) -> tuple[float, float, float, float]:
    return (
        min(1.0, max(0.0, box.left / width)),
        min(1.0, max(0.0, box.top / height)),
        min(1.0, max(0.0, box.right / width)),
        min(1.0, max(0.0, box.bottom / height)),
    )


def title_for(index: int, category: str, text: list[dict]) -> str:
    candidates = sorted(text, key=lambda item: item["box"].height * math.sqrt(max(1.0, item["box"].width)), reverse=True)
    for candidate in candidates:
        value = candidate["text"].strip(PUNCTUATION)
        if 2 <= len(value) <= 24 and plausible_text(value):
            return value
    return f"{category}模板 {index:03d}"


def kotlin_string(value: str) -> str:
    clean = "".join(character for character in value.replace("\ufeff", "") if character in "\n\t" or ord(character) >= 32)
    return json.dumps(clean, ensure_ascii=False).replace("$", "\\$")


def kfloat(value: float) -> str:
    return f"{min(1.0, max(0.0, value)):.5f}f"


def text_kotlin(item: dict, width: int, height: int) -> str:
    left, top, right, bottom = normalized_box(item["box"], width, height)
    box_height = bottom - top
    center = (left + right) / 2.0
    alignment = "CENTER" if abs(center - 0.5) < 0.10 and right - left > 0.28 else ("RIGHT" if left > 0.55 else "LEFT")
    emphasis = box_height > 0.075 or (box_height > 0.052 and len(item["text"]) <= 12)
    return (
        f"SourceTextSpec({kotlin_string(item['text'])}, {kfloat(left)}, {kfloat(top)}, {kfloat(right)}, {kfloat(bottom)}, "
        f"{str(emphasis).lower()}, {kotlin_string(alignment)}, {str(bool(item['crossChecked'])).lower()})"
    )


def code_kotlin(item: dict, width: int, height: int) -> str:
    left, top, right, bottom = normalized_box(item["box"], width, height)
    return (
        f"SourceCodeSpec({kotlin_string(item['type'])}, {kfloat(left)}, {kfloat(top)}, {kfloat(right)}, {kfloat(bottom)}, "
        f"{kotlin_string(item['content'])})"
    )


def shape_kotlin(item: dict, width: int, height: int) -> str:
    left, top, right, bottom = normalized_box(item["box"], width, height)
    normalized_stroke = item["stroke"] / max(1.0, min(width, height))
    return (
        f"SourceShapeSpec({kotlin_string(item['kind'])}, {kfloat(left)}, {kfloat(top)}, {kfloat(right)}, {kfloat(bottom)}, "
        f"{normalized_stroke:.5f}f)"
    )


def spec_kotlin(spec: dict) -> str:
    text = ", ".join(spec["textKotlin"])
    codes = ", ".join(spec["codeKotlin"])
    shapes = ", ".join(spec["shapeKotlin"])
    return (
        "    SourceTemplateSpec("
        f"{kotlin_string(spec['id'])}, {kotlin_string(spec['title'])}, {kotlin_string(spec['category'])}, "
        f"{spec['widthMm']:.2f}f, {spec['heightMm']:.2f}f, {kotlin_string(spec['decorResource'])}, "
        f"listOf({text}), listOf({codes}), listOf({shapes})),"
    )


def manual_templates() -> list[dict]:
    def text(value: str, left: float, top: float, right: float, bottom: float, emphasis: bool = False, alignment: str = "LEFT") -> str:
        return f"SourceTextSpec({kotlin_string(value)}, {kfloat(left)}, {kfloat(top)}, {kfloat(right)}, {kfloat(bottom)}, {str(emphasis).lower()}, {kotlin_string(alignment)}, true)"

    def code(value: str, left: float, top: float, right: float, bottom: float) -> str:
        return f"SourceCodeSpec(\"QR_CODE\", {kfloat(left)}, {kfloat(top)}, {kfloat(right)}, {kfloat(bottom)}, {kotlin_string(value)})"

    def shape(kind: str, left: float, top: float, right: float, bottom: float, stroke: float = 0.006) -> str:
        return f"SourceShapeSpec({kotlin_string(kind)}, {kfloat(left)}, {kfloat(top)}, {kfloat(right)}, {kfloat(bottom)}, {stroke:.5f}f)"

    templates = [
        {
            "id": "provided-milk-tea", "title": "XX奶茶", "category": "餐饮服务", "widthMm": 40.0, "heightMm": 30.2,
            "textKotlin": [text("XX奶茶", .31, .08, .69, .23, True, "CENTER"), text("品名：杨枝甘露", .27, .38, .73, .50), text("规格：中杯", .27, .56, .63, .68), text("价格：18", .27, .72, .57, .84)],
            "codeKotlin": [], "shapeKotlin": [], "decorResource": "",
        },
        {
            "id": "provided-rural-bank-blue", "title": "河南农信支付宝收款码", "category": "收款码", "widthMm": 55.0, "heightMm": 76.0,
            "textKotlin": [text("河南省农村信用社（农商银行）", .06, .03, .52, .09, True), text("扫扫天下 金融还不贵", .59, .03, .95, .09, True), text("推荐使用支付宝", .27, .20, .73, .26, True, "CENTER"), text("支付宝支付笔笔得积分", .25, .72, .75, .77, False, "CENTER"), text("银联  河南农信  支付宝  云闪付  微信支付", .10, .91, .90, .96, False, "CENTER")],
            "codeKotlin": [code("河南省农村信用社", .25, .29, .75, .67)], "shapeKotlin": [shape("ROUNDED_RECTANGLE", .20, .27, .80, .70, .008)], "decorResource": "",
        },
        {
            "id": "provided-sample-check", "title": "土壤样品普查", "category": "医药行业", "widthMm": 55.0, "heightMm": 32.1,
            "textKotlin": [text("样品编码：610881010200006310", .03, .10, .50, .22), text("样品类型：表层样", .03, .32, .43, .44), text("地类名称：水渠地", .03, .55, .43, .67), text("采样日期：2022年09月02日", .03, .77, .53, .89)],
            "codeKotlin": [code("610881010200006310", .51, .16, .83, .72)], "shapeKotlin": [], "decorResource": "",
        },
        {
            "id": "provided-deppon-logistics", "title": "德邦物流", "category": "仓储物流", "widthMm": 40.0, "heightMm": 49.8,
            "textKotlin": [text("德邦物流", .25, .05, .75, .16, True, "CENTER"), text("品名：", .05, .24, .22, .31), text("规格：", .05, .43, .22, .50), text("数量：", .05, .63, .22, .70), text("日期：", .05, .82, .22, .89)],
            "codeKotlin": [], "shapeKotlin": [shape("LINE", .19, .30, .89, .31), shape("LINE", .19, .49, .89, .50), shape("LINE", .19, .69, .89, .70), shape("LINE", .19, .88, .89, .89)], "decorResource": "",
        },
        {
            "id": "provided-cainiao-station", "title": "菜鸟快递驿站", "category": "仓储物流", "widthMm": 40.0, "heightMm": 29.8,
            "textKotlin": [text("CAI\nNIAO", .02, .04, .14, .22, False), text("快递驿站", .33, .15, .70, .29, True, "CENTER"), text("9096", .28, .35, .63, .53, True, "CENTER"), text("有运费险可到驿站\n0元寄件", .42, .69, .90, .88)],
            "codeKotlin": [code("PRT", .07, .60, .35, .92)], "shapeKotlin": [], "decorResource": "",
        },
        {
            "id": "provided-warehouse-box", "title": "仓库运输", "category": "仓储物流", "widthMm": 20.0, "heightMm": 20.0,
            "textKotlin": [text("箱号", .04, .10, .30, .25), text("1888箱", .35, .07, .94, .26, True), text("仓库", .04, .29, .30, .43), text("XX仓库", .39, .27, .94, .45, True), text("为保证样品运输安全", .04, .48, .94, .60), text("请务必用原箱包装好", .04, .62, .94, .74), text("谢谢您的配合！", .04, .79, .77, .91)],
            "codeKotlin": [], "shapeKotlin": [], "decorResource": "",
        },
        {
            "id": "provided-rural-bank-red", "title": "金燕e付收款码", "category": "收款码", "widthMm": 55.0, "heightMm": 76.6,
            "textKotlin": [text("卢氏农商银行", .04, .03, .36, .08, True), text("扫扫天下 金融还不贵", .56, .03, .95, .08, True), text("银联  河南农信  云闪付  支付宝  微信", .17, .14, .83, .21, False, "CENTER"), text("金燕e付", .25, .25, .75, .34, True, "CENTER"), text("陈某某", .42, .70, .58, .75, False, "CENTER"), text("服务热线：0398-7874276", .55, .94, .94, .97)],
            "codeKotlin": [code("PRT", .26, .37, .74, .67)], "shapeKotlin": [shape("ROUNDED_RECTANGLE", .10, .11, .90, .82, .008)], "decorResource": "",
        },
        {
            "id": "starter-live-commerce", "title": "直播订单发货贴", "category": "直播带货", "widthMm": 50.0, "heightMm": 30.0,
"textKotlin": [text("直播订单发货贴", .08, .08, .92, .23, True, "CENTER"), text("直播间：素打好物", .08, .31, .70, .42), text("粉丝昵称：________", .08, .48, .75, .59), text("商品：____________", .08, .65, .75, .76), text("订单号：202608120001", .08, .82, .86, .93)],
            "codeKotlin": [], "shapeKotlin": [], "decorResource": "",
        },
    ]
    return templates


def generate(args: argparse.Namespace) -> None:
    categories = json.loads(args.categories.read_text(encoding="utf-8-sig"))
    ocr_records = json.loads(args.ocr.read_text(encoding="utf-8-sig"))
    code_records = json.loads(args.codes.read_text(encoding="utf-8-sig"))
    code_by_source = {record["source"]: record for record in code_records}
    args.decor.mkdir(parents=True, exist_ok=True)
    args.kotlin.parent.mkdir(parents=True, exist_ok=True)

    specs: list[dict] = []
    quality: list[dict] = []
    for index, record in enumerate(ocr_records, start=1):
        image_path = args.source / record["source"]
        with Image.open(image_path) as opened:
            image = opened.convert("RGB")
        width, height = image.size
        if width <= 0 or height <= 0:
            continue
        source_width_mm = max(MIN_WIDTH_MM, width / 8.0)
        width_mm = min(MAX_SOURCE_WIDTH_MM, source_width_mm)
        height_mm = max(5.0, height / width * width_mm)
        texts = merge_ocr(record)
        codes = decoded_codes(code_by_source.get(record["source"], {}), width, height)
        exclusions = [item["box"] for item in texts] + [item["box"] for item in codes]
        array = np.asarray(image, dtype=np.uint8)
        shapes = detect_lines(array, exclusions)
        combined_text = " ".join(item["text"] for item in texts)
        category = category_for(categories, combined_text)
        title = title_for(index, category, texts)
        decor, _meaningful_decor = prepare_decor(image, texts, codes, shapes)
        # Keep one unlocked, replaceable source-art layer even for intentionally blank labels.
        # That preserves every source file as a real editable template instead of silently
        # dropping an all-white or outline-only design during the content separation pass.
        has_decor = True
        decor_resource = ""
        if has_decor:
            decor_resource = f"source_decor_{index:03d}"
            target_width = max(80, int(round(width_mm * DOTS_PER_MM)))
            target_height = max(40, int(round(height_mm * DOTS_PER_MM)))
            decor = decor.resize((target_width, target_height), Image.Resampling.LANCZOS)
            decor.save(args.decor / f"{decor_resource}.webp", "WEBP", quality=91, method=6)
        spec = {
            "id": f"source-{index:03d}",
            "title": title,
            "category": category,
            "widthMm": width_mm,
            "heightMm": height_mm,
            "decorResource": decor_resource,
            "textKotlin": [text_kotlin(item, width, height) for item in texts],
            "codeKotlin": [code_kotlin(item, width, height) for item in codes],
            "shapeKotlin": [shape_kotlin(item, width, height) for item in shapes],
        }
        specs.append(spec)
        quality.append(
            {
                "id": spec["id"],
                "source": record["source"],
                "title": title,
                "category": category,
                "widthMm": round(width_mm, 2),
                "heightMm": round(height_mm, 2),
                "text": len(texts),
                "crossCheckedText": sum(bool(item["crossChecked"]) for item in texts),
                "codes": len(codes),
                "shapes": len(shapes),
                "editableDecor": has_decor,
                "ocrError": record.get("error", ""),
                "recognizedText": [item["text"] for item in texts],
                "singlePassText": [item["text"] for item in texts if not item["crossChecked"]],
            }
        )
        if index % 25 == 0 or index == len(ocr_records):
            print(f"[{index:03d}/{len(ocr_records):03d}] {record['source']}", flush=True)

    specs.extend(manual_templates())
    entries = [spec_kotlin(spec) for spec in specs]
    chunk_size = 18
    chunk_names: list[str] = []
    chunks: list[str] = []
    for start in range(0, len(entries), chunk_size):
        name = f"sourceChunk{start // chunk_size}"
        chunk_names.append(name)
        chunks.append(f"private fun {name}() = listOf(\n" + "\n".join(entries[start:start + chunk_size]) + "\n)\n")
    output = KOTLIN_HEADER
    output += f"internal const val SOURCE_TEMPLATE_COUNT: Int = {len(specs)}\n\n"
    output += "internal val sourceTemplateSpecs: List<SourceTemplateSpec> by lazy {\n    buildList {\n"
    for name in chunk_names:
        output += f"        addAll({name}())\n"
    output += "    }\n}\n\n" + "\n".join(chunks)
    args.kotlin.write_text(output, encoding="utf-8")

    category_counts: dict[str, int] = {item["name"]: 0 for item in categories}
    for spec in specs:
        category_counts[spec["category"]] += 1
    report = {
        "sourceTemplates": len(ocr_records),
        "providedTemplates": 7,
        "starterTemplates": 1,
        "totalTemplates": len(specs),
        "categories": category_counts,
        "decodedCodes": sum(item["codes"] for item in quality),
        "crossCheckedText": sum(item["crossCheckedText"] for item in quality),
        "editableText": sum(item["text"] for item in quality),
        "details": quality,
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "details"}, ensure_ascii=False), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--ocr", type=Path, required=True)
    parser.add_argument("--codes", type=Path, required=True)
    parser.add_argument("--categories", type=Path, required=True)
    parser.add_argument("--decor", type=Path, required=True)
    parser.add_argument("--kotlin", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    generate(parser.parse_args())


if __name__ == "__main__":
    main()
