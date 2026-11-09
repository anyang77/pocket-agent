import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.pocketagent.agent.GeneratedImage;
import dev.pocketagent.agent.ImageGenerationClient;
import dev.pocketagent.data.AiSettings;
import dev.pocketagent.data.SecretStore;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

/** Explicitly billed, one-shot integration probe. No default model override or POST retry.
 * Run under the app UID with app_process; reads the existing key through SecretStore,
 * never prints credentials, and does not change preferences. Args: model output-directory.
 */
public final class ImageGenerationSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected model and output directory");
        android.os.Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(instance);
        Context packageContext = system.createPackageContext("dev.pocketagent", 0);
        Context context = new android.content.ContextWrapper(packageContext) {
            @Override public Context getApplicationContext() { return this; }
        };
        android.content.SharedPreferences prefs = context.getSharedPreferences("pocket_agent_settings", Context.MODE_PRIVATE);
        String active = prefs.getString("active_profile_id", "");
        JSONArray profiles = new JSONArray(prefs.getString("model_profiles", "[]"));
        JSONObject selected = null;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject item = profiles.getJSONObject(i);
            if (active.equals(item.optString("id"))) selected = item;
        }
        if (selected == null) throw new IllegalStateException("No active service profile");
        // app_process does not run ActivityThread.handleBindApplication's provider setup.
        Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null);
        String key = new SecretStore(context).getApiKey(active);
        if (key.isEmpty()) throw new IllegalStateException("Existing service credential unavailable");
        AiSettings settings = new AiSettings(selected.getString("base_url"), args[0], key,
            128000, "", "auto", null);
        // Measure local framing separately from the network, without logging image/prompt/key data.
        byte[] sample = ("{\"data\":[{\"b64_json\":\"" + "A".repeat(4 * 1024 * 1024) + "\"}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long scanStart = System.nanoTime();
        dev.pocketagent.agent.JsonResponseReader.INSTANCE.read(new java.io.ByteArrayInputStream(sample), 48 * 1024 * 1024, received -> kotlin.Unit.INSTANCE);
        System.out.println("LOCAL_JSON_SCAN bytes=" + sample.length + " elapsed_ms=" + (System.nanoTime() - scanStart) / 1_000_000);
        final long networkStart = System.nanoTime();
        OkHttpClient http = new OkHttpClient.Builder()
            .eventListener(new okhttp3.EventListener() {
                @Override public void responseHeadersEnd(okhttp3.Call call, okhttp3.Response response) {
                    System.out.println("RESPONSE_HEADERS elapsed_ms=" + (System.nanoTime() - networkStart) / 1_000_000
                        + " status=" + response.code() + " protocol=" + response.protocol()
                        + " content_type=" + response.header("Content-Type")
                        + " content_length=" + response.header("Content-Length")
                        + " content_encoding=" + response.header("Content-Encoding"));
                }
            })
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build();
        ImageGenerationClient client = new ImageGenerationClient(http);
        String prompt = "可爱Q版木之本樱，全身构图，栗色短发，翠绿色大眼睛，甜美笑容。经典粉红色魔法裙、白色翅膀装饰、红色蝴蝶结，可爱的帽子，手持星之杖。小可在身旁开心飞翔，樱花花瓣、柔和粉色天空与闪闪星光。日系动漫插画，细腻线条，明亮柔和，温暖梦幻，无文字、无水印。";
        long start = System.currentTimeMillis();
        System.out.println("IMAGE_PROBE_START model=" + args[0] + " size=1024x1536 requests=1");
        System.out.flush();
        try {
            final long[] last = {0, 0};
            GeneratedImage image = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> client.generate(settings, prompt, "1024x1536", progress -> {
                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed - last[0] >= 5000 || progress.getReceivedBytes() == 0) {
                        System.out.println("IMAGE_PROGRESS stage=" + progress.getStage()
                            + " bytes=" + progress.getReceivedBytes() + " elapsed_ms=" + elapsed
                            + " interval_bytes=" + (progress.getReceivedBytes() - last[1]));
                        System.out.flush();
                        last[0] = elapsed; last[1] = progress.getReceivedBytes();
                    }
                    return kotlin.Unit.INSTANCE;
                }, continuation));
            byte[] bytes = image.getBytes();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new IllegalStateException("Returned bytes do not decode as an image");
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            bitmap.recycle();
            File dir = new File(args[1]);
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("Cannot create output directory");
            File output = File.createTempFile("image-probe-", "." + image.getExtension(), dir);
            Files.write(output.toPath(), bytes);
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", b & 255));
            System.out.println("IMAGE_PROBE_SUCCESS path=" + output.getAbsolutePath()
                + " width=" + width + " height=" + height + " bytes=" + bytes.length
                + " sha256=" + digest + " elapsed_ms=" + (System.currentTimeMillis() - start));
        } catch (Exception error) {
            // Client API errors are bounded and redacted; never dump request/response objects.
            String message = error instanceof dev.pocketagent.agent.AgentException
                ? error.getMessage() : error.getClass().getSimpleName();
            System.out.println("IMAGE_PROBE_FAILED " + message + " elapsed_ms=" + (System.currentTimeMillis() - start));
            System.exit(1);
        } finally {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
    }
}
