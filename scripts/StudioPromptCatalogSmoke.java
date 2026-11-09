import dev.pocketagent.ui.StudioCommunityPrompt;
import dev.pocketagent.ui.StudioPromptSourcesKt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Local parser integration check against downloaded public README files. No generation requests. */
class StudioPromptCatalogSmoke {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            List<StudioCommunityPrompt> prompts = StudioPromptSourcesKt.parseStudioCommunityPrompts(Files.readString(Path.of(arg)));
            if (prompts.size() < 10) throw new AssertionError("Too few parsed prompts: " + arg);
            long attributed = prompts.stream().filter(p -> !p.getAuthor().isBlank()).count();
            long linked = prompts.stream().filter(p -> p.getOriginalUrl() != null).count();
            if (attributed == 0 || linked == 0) throw new AssertionError("Attribution not parsed: " + arg);
            System.out.println(Path.of(arg).getFileName() + " parsed=" + prompts.size() + " authors=" + attributed + " source_links=" + linked);
        }
    }
}
