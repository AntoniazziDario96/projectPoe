package it.poemeta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Locale;

/**
 * Lingua dell'interfaccia (italiano o inglese). Si cambia con {@code ?lang=en} / {@code ?lang=it}
 * e la scelta resta salvata in un cookie per un anno.
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver r = new CookieLocaleResolver("lang");
        r.setDefaultLocale(Locale.ITALIAN);
        r.setCookieMaxAge(Duration.ofDays(365));
        return r;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor i = new LocaleChangeInterceptor();
        i.setParamName("lang");
        i.setIgnoreInvalidLocale(true);
        registry.addInterceptor(i);
    }
}
