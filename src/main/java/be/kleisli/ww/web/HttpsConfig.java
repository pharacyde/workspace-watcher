package be.kleisli.ww.web;

import be.kleisli.ww.core.WatcherProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Serves HTTPS when a keystore is there, and plain HTTP when it is not.
 *
 * <p>Presence of the file is the switch, so generating a certificate is the entire act of enabling
 * it - there is no separate flag to set and then forget. {@code scripts/dev-cert.sh} makes one.
 *
 * <p>The reason to bother, on a dashboard that only listens on loopback: Safari will not grant
 * notification permission on a plain http origin. A certificate the system trusts turns that from
 * "blocked by browser" into a working feature. A self-signed one does not - the browser has to
 * trust it, which is what mkcert is for.
 */
@Component
public class HttpsConfig implements WebServerFactoryCustomizer<ConfigurableWebServerFactory> {

  private static final Logger log = LoggerFactory.getLogger(HttpsConfig.class);

  private final WatcherProperties props;

  public HttpsConfig(WatcherProperties props) {
    this.props = props;
  }

  @Override
  public void customize(ConfigurableWebServerFactory factory) {
    String location = props.getKeystore();
    if (location == null || location.isBlank()) {
      return;
    }
    Path keystore = Path.of(location).toAbsolutePath().normalize();
    if (!Files.isRegularFile(keystore)) {
      log.info("serving http; no keystore at {}", keystore);
      return;
    }

    Ssl ssl = new Ssl();
    ssl.setEnabled(true);
    ssl.setKeyStore("file:" + keystore);
    ssl.setKeyStorePassword(props.getKeystorePassword());
    ssl.setKeyStoreType("PKCS12");
    factory.setSsl(ssl);
    log.info("serving https from {}", keystore);
  }
}
