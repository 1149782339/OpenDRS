package io.opendrs.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Static bridge to Spring's auto-configured {@link MessageSource}. Kept as a util (rather than an
 * injected service) so translation works from static factories and utility code without plumbing a
 * bean through every call site.
 *
 * <p>The locale is resolved per call from {@link LocaleContextHolder} — in a web request that is the
 * request's {@code Accept-Language}; never cache the resulting text beyond the request boundary.
 */
@Component
public class I18nUtils {

    private static MessageSource messageSource;

    /** Spring injects the auto-configured MessageSource when this component is created. */
    public I18nUtils(MessageSource messageSource) {
        I18nUtils.messageSource = messageSource;
    }

    public static String getMessage(ErrorMessage message, Object... args) {
        // Stringify args: MessageFormat would otherwise localize numbers/dates per locale
        // (e.g. 999999 -> "999,999"), but message args are identifiers meant to stay verbatim.
        Object[] verbatim = args == null ? null : java.util.Arrays.stream(args)
                .map(arg -> arg == null ? null : String.valueOf(arg))
                .toArray();
        return messageSource.getMessage(message.getKey(), verbatim, LocaleContextHolder.getLocale());
    }
}
