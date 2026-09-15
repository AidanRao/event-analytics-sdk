package top.aidanrao.analytics.example;

import java.util.Collections;
import top.aidanrao.analytics.Analytics;

public final class JavaExample {
    private JavaExample() {}
    public static void track(Analytics sdk) {
        sdk.setIdentity(Collections.singletonMap("user_id", "java-user"));
        sdk.track("button_clicked", Collections.singletonMap("source", "android-java"));
        sdk.setIdentity(Collections.emptyMap());
    }
}
