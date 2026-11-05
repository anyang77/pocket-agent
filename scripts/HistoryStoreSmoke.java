import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketagent.data.ConversationJson;
import dev.pocketagent.data.ConversationStore;
import dev.pocketagent.data.StoredConversation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Run with app_process against a PRIVATE fixture copy, never the live history directory. */
public final class HistoryStoreSmoke {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static ConversationStore store(File dir) {
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return dir; }
        };
        return new ConversationStore(context);
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        check(root.mkdirs() || root.isDirectory(), "Cannot create private test directory");
        File file = new File(root, "conversations.json");
        check(!file.exists(), "Use a fresh test directory, not live history");
        byte[] valid = "{\"version\":2,\"conversations\":[{\"id\":1,\"title\":\"test\",\"messages\":[{\"id\":1,\"role\":\"USER\",\"content\":\"test\",\"timestamp\":1}]}]}".getBytes(StandardCharsets.UTF_8);
        ConversationStore fresh = store(root);
        try { fresh.save(Collections.emptyList()); throw new AssertionError("Saved before load"); }
        catch (Exception expected) { check(expected instanceof IOException, "Wrong write error"); }
        check(fresh.load().isEmpty() && fresh.getLoadFailure() == null, "Missing history must load cleanly");
        Files.write(file.toPath(), valid);
        ConversationStore s = store(root);
        List<StoredConversation> data = s.load();
        check(data.size() == 1 && s.getLoadFailure() == null, "Load failed");
        s.save(data);
        check(store(root).load().equals(data), "Reopen did not preserve history");
        byte[] before = Files.readAllBytes(file.toPath());
        List<StoredConversation> broken = new AbstractList<StoredConversation>() {
            public int size() { return 1; }
            public StoredConversation get(int index) { throw new AssertionError("injected write failure"); }
        };
        try { s.save(broken); throw new IllegalStateException("Failure was swallowed"); }
        catch (AssertionError expected) { check(expected.getMessage().equals("injected write failure"), "Wrong error"); }
        check(Arrays.equals(before, Files.readAllBytes(file.toPath())), "Rollback changed previous file");
        check(!new File(root, "conversations.json.new").exists(), "Partial write left behind");
        Files.write(file.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        ConversationStore corrupt = store(root);
        check(corrupt.load().isEmpty() && corrupt.getLoadFailure() != null, "Corruption hidden");
        try { corrupt.save(data); throw new AssertionError("Overwrote corrupt history"); }
        catch (Exception expected) { check(expected instanceof IOException, "Wrong write error"); }
        check(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals("{broken"), "Corrupt original changed");
        for (String incomplete : Arrays.asList("{\"conversations\":[{\"id\":1}]}",
                "{\"conversations\":[{\"id\":1,\"messages\":null}]}")) {
            Files.write(file.toPath(), incomplete.getBytes(StandardCharsets.UTF_8));
            ConversationStore damaged = store(root);
            check(damaged.load().isEmpty() && damaged.getLoadFailure() != null, "Incomplete structure accepted");
            try { damaged.save(data); throw new AssertionError("Overwrote incomplete history"); }
            catch (Exception expected) { check(expected instanceof IOException, "Wrong structure error"); }
            check(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals(incomplete),
                "Incomplete original changed");
        }
        System.out.println("PASS: structurally incomplete history remains protected from overwrite");
        file.delete();
        Files.write(new File(root, "conversations.json.bak").toPath(), valid);
        check(store(root).load().size() == 1 && file.exists(), "Legacy backup not recovered");
        System.out.println("PASS: missing file, load-before-save guard, save/reopen, rollback on Error, corruption protection, legacy backup recovery");
        if (args.length > 1) {
            Files.copy(new File(args[1]).toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ConversationStore real = store(root);
            List<StoredConversation> history = real.load();
            check(real.getLoadFailure() == null && history.size() > 0, "Private fixture failed to load");
            real.save(history);
            check(store(root).load().equals(history), "Private fixture did not round trip");
            System.out.println("PASS: private real-history atomic save/reopen; conversations=" + history.size() + "; bytes=" + file.length() + "; heapLimit=" + Runtime.getRuntime().maxMemory());
        }
    }
}
