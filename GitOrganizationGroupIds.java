///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.0
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.0
//DEPS com.squareup.okhttp3:okhttp:4.12.0
//DEPS info.picocli:picocli:4.7.6
//DEPS org.kohsuke:github-api:1.327

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import picocli.CommandLine;

@CommandLine.Command(name = "GitOrganizationGroupIds", mixinStandardHelpOptions = true, version = "GitOrganizationGroupIds 0.1", description = """
        The GitOrganizationGroupIds script writes to a file the Maven group IDs for repositories in a list of GitHub organizations
        """)
public class GitOrganizationGroupIds implements Runnable {
    public static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(GitOrganizationGroupIds.class.getPackageName());

    @CommandLine.Parameters(index = "0", description = "The organizations to check", split = ",")
    private List<String> organizations;

    @CommandLine.Option(names = { "-o",
            "--output-file" }, description = "Name of the output file", defaultValue = "group-ids.md")
    private String outputFile;

    @CommandLine.Option(names = { "-a",
            "--include-archived" }, description = "Whether archived repositories should be included", defaultValue = "true")
    private boolean includeArchived;

    private final String cacheDir = System.getProperty("user.home") + "/.cache/git-organization-group-ids-cache";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GitOrganizationGroupIds()).execute(args);
        System.exit(exitCode);
    }

    @SuppressWarnings("CallToPrintStackTrace")
    @Override
    public void run() {
        try {
            // Connect to GitHub
            GitHub github = setupGitHubClient();

            try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
                TreeSet<String> activeGroupIds = new TreeSet<>();
                TreeSet<String> archivedGroupIds = new TreeSet<>();

                writer.println("## Repository details");
                writer.println();
                writer.println("| Organization | Repository | Archived | Default Branch | Group ID |");
                writer.println("| --- | --- | --- | --- | --- |");

                for (String organization : organizations.stream().sorted().toList()) {
                    GHOrganization org = github.getOrganization(organization);
                    if (org == null) {
                        log.severe("Organization not found: " + organization);
                        continue;
                    }
                    log.info("❇️ Preparing to list repositories for organization " + org.getLogin());

                    List<GHRepository> allRepos = org.listRepositories().toList();
                    log.info("Found " + allRepos.size() + " candidate repositories");

                    List<GHRepository> filteredRepos = allRepos.stream()
                            .filter(repo -> includeArchived || !repo.isArchived())
                            .sorted(Comparator.comparing(GHRepository::getName))
                            .toList();

                    for (GHRepository repo : filteredRepos) {
                        String groupId = extractGroupId(repo);
                        writer.println("| " + org.getLogin() + " | " + repo.getName() + " | " + repo.isArchived() + " | " + repo.getDefaultBranch() + " | " + groupId + " |");
                        if (!groupId.equals("N/A") && !groupId.equals("error")) {
                            if (repo.isArchived()) {
                                archivedGroupIds.add(groupId);
                            } else {
                                activeGroupIds.add(groupId);
                            }
                        }
                    }
                    log.info("Recorded " + filteredRepos.size() + " matching repositories for organization " + org.getLogin());
                }

                writer.println();
                writer.println("## Active GroupId List");
                writer.println();
                for (String groupId : activeGroupIds) {
                    writer.println(groupId + "  ");
                }

                writer.println();
                writer.println("## Archived GroupId List");
                writer.println();
                archivedGroupIds.removeAll(activeGroupIds);
                for (String groupId : archivedGroupIds) {
                    writer.println(groupId + "  ");
                }
            }
            log.info("✔️ Group ID list written to " + outputFile);
        } catch (IOException e) {
            log.severe("Error: " + e);
            e.printStackTrace();
        }
    }

    private String extractGroupId(GHRepository repo) {
        try {
            GHContent content;
            try {
                content = repo.getFileContent("pom.xml");
            } catch (GHFileNotFoundException fnfe) {
                return "N/A";
            }
            if (content == null) {
                return "N/A";
            }

            try (InputStream is = content.read()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                factory.setValidating(false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setExpandEntityReferences(false);

                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(is);
                Element root = doc.getDocumentElement();
                if (root == null) {
                    return "N/A";
                }

                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (node.getNodeType() == Node.ELEMENT_NODE && "groupId".equals(node.getNodeName())) {
                        String text = node.getTextContent();
                        return text != null ? text.trim() : "N/A";
                    }
                }
                return "N/A";
            }
        } catch (Exception e) {
            log.warning("⚠️ Error extracting groupId for repository " + repo.getName() + ": " + e.getMessage());
            return "error";
        }
    }

    /**
     * Set up GitHub client with caching to reduce API calls
     */
    public GitHub setupGitHubClient() throws IOException {

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();

        if (ensureDirectoryExists(cacheDir)) {
            log.finest("Cache directory: " + cacheDir);
            Cache cache = new Cache(Path.of(cacheDir).toFile(), 10 * 1024 * 1024); // 10MB cache
            clientBuilder.cache(cache);
        } else {
            log.finest("Cannot create cache directory at " + cacheDir + " -- will use a non-caching GitHub API connector");
        }

        log.finest("Creating GitHub API connector");
        var connector = new OkHttpGitHubConnector(clientBuilder.build());

        GitHub gh = GitHubBuilder.fromPropertyFile()
                .withConnector(connector)
                .build();
        log.finest("Connected successfully");
        return gh;
    }

    private boolean ensureDirectoryExists(String dirPath) {
        Path path = Path.of(dirPath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                return true;
            } catch (IOException e) {
                log.warning("⚠️ Failed to create directory: " + dirPath + " -- request caching will not be available");
                return false;
            }
        }
        return true;
    }
}
