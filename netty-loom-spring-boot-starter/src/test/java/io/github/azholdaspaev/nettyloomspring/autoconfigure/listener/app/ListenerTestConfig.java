package io.github.azholdaspaev.nettyloomspring.autoconfigure.listener.app;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the recorder the way an application would: through Boot's
 * {@code ServletListenerRegistrationBean}, which calls {@code ServletContext.addListener} from a
 * {@code ServletContextInitializer}. That is the exact route {@code @WebListener} classes and
 * {@code HttpSessionEventPublisher} take, and the one that used to abort startup (issue #17).
 */
@Configuration(proxyBeanMethods = false)
public class ListenerTestConfig {

    @Bean
    public RecordingListener recordingListener() {
        return new RecordingListener();
    }

    @Bean
    public ServletListenerRegistrationBean<RecordingListener> recordingListenerRegistration(
        RecordingListener listener) {
        return new ServletListenerRegistrationBean<>(listener);
    }
}
