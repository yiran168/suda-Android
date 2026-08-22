/*
 * Security hardening for an optional POI timestamp client that is not used by 素打.
 *
 * Upstream's explicitly named UnsafeTrustManager accepts every certificate. Keeping that class
 * in the shaded runtime would be an unnecessary foot-gun, so this binary-compatible replacement
 * delegates to Android/JVM's default X.509 trust manager instead.
 */
package org.apache.poi.poifs.crypt.dsig.services;

import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class TimeStampSimpleHttpClient$UnsafeTrustManager implements X509TrustManager {
    private final X509TrustManager delegate;

    private TimeStampSimpleHttpClient$UnsafeTrustManager() {
        delegate = platformTrustManager();
    }

    TimeStampSimpleHttpClient$UnsafeTrustManager(TimeStampSimpleHttpClient$1 ignored) {
        this();
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    private static X509TrustManager platformTrustManager() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((java.security.KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager) {
                    return (X509TrustManager) manager;
                }
            }
            throw new IllegalStateException("Platform X.509 trust manager is unavailable");
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to initialize platform trust manager", error);
        }
    }
}
