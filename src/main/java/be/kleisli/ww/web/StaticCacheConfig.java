package be.kleisli.ww.web;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Caching rules for the built frontend.
 *
 * <p>Asset filenames carry a content hash, so they can be cached for a year. {@code index.html}
 * cannot be cached at all: it is the file that names those assets, and a browser holding an old
 * copy keeps loading an old bundle long after a rebuild. That failure is indistinguishable from a
 * broken feature - it cost a real "the diff panel is broken" report that a hard refresh fixed.
 */
@Component
public class StaticCacheConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/index.html", "/")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.noStore())
        .resourceChain(false)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String path, Resource location) throws IOException {
                Resource resource = new ClassPathResource("static/index.html");
                return resource.exists() ? resource : null;
              }
            });
  }
}
