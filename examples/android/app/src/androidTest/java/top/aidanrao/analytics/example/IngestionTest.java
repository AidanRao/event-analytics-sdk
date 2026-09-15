package top.aidanrao.analytics.example;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import java.util.concurrent.TimeUnit;
import top.aidanrao.analytics.Analytics;
import top.aidanrao.analytics.FlushResult;

/** Requires the local Worker with app demo and allow_no_origin=true, forwarded on port 8787. */
@RunWith(AndroidJUnit4.class)
public class IngestionTest {
    @Test public void testPublishedAarIngestion() throws Exception {
        Analytics sdk = new Analytics.Builder(InstrumentationRegistry.getInstrumentation().getTargetContext(), "http://127.0.0.1:8787/v1/events", "demo").build();
        try {
            sdk.track("android_instrumentation");
            JavaExample.track(sdk);
            FlushResult result = sdk.flush().get(20, TimeUnit.SECONDS);
            assertEquals(2, result.getAccepted());
            assertEquals(0, result.getFailed());
        } finally { sdk.close().get(20, TimeUnit.SECONDS); }
    }
}
