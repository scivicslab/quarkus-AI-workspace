package com.scivicslab.aiworkspace.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks one GitHub repository for the two versions it states.
 *
 * <p>The newest release's tag is read through the REST API, the same URL
 * {@code Download Latest Release} already uses. The version a build would produce is read from the
 * default branch's {@code pom.xml}: a version whose name ends in {@code -SNAPSHOT} is never
 * published as a release, so that file is the only place it is stated
 * ({@code ToolVersions_260907_oo01}).
 */
@ApplicationScoped
public class GitHubVersionFetcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The first version element that follows an artifactId element, which is the project's own. */
    private static final Pattern PROJECT_VERSION = Pattern.compile(
            "</groupId>\\s*<artifactId>[^<]+</artifactId>\\s*<version>([^<]+)</version>",
            Pattern.DOTALL);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * @param repository owner and name, for example {@code scivicslab/html-saurus}
     * @return both versions; either is empty when the repository does not state it
     * @throws Exception when neither could be read
     */
    public RemoteVersions fetch(String repository) throws Exception {
        // No pause here: the caller reads one repository per request, and the browser leaves
        // three seconds between them (ToolVersions_260907_oo01).
        String latestRelease = fetchLatestRelease(repository);
        String latestSnapshot = fetchLatestSnapshot(repository);
        return new RemoteVersions(latestRelease, latestSnapshot);
    }

    /**
     * Reads the newest release's tag.
     *
     * <p>A repository with no release at all answers 404. That is not a failure: it means there is
     * nothing to report, so the version comes back empty.
     */
    private String fetchLatestRelease(String repository) throws Exception {
        HttpResponse<String> response = get(
                "https://api.github.com/repos/" + repository + "/releases/latest",
                "application/vnd.github+json");
        if (response.statusCode() == 404) return "";
        if (response.statusCode() != 200) {
            throw new IllegalStateException("releases/latest answered HTTP " + response.statusCode());
        }
        JsonNode tag = MAPPER.readTree(response.body()).path("tag_name");
        if (tag.isMissingNode() || tag.asText().isBlank()) return "";
        String name = tag.asText();
        // Tags are written v2.2.0; the pom states 2.2.0. Drop the v so the two can be read together.
        return name.startsWith("v") ? name.substring(1) : name;
    }

    /**
     * Reads the version the default branch's {@code pom.xml} declares.
     *
     * <p>{@code HEAD} in the path resolves to whatever the repository's default branch is, so no
     * branch name is written here. Which branch that is has changed once already: the repositories
     * under {@code scivicslab} were split between {@code master} and {@code main} and were renamed
     * to {@code main}, and nothing here had to change.
     */
    private String fetchLatestSnapshot(String repository) throws Exception {
        HttpResponse<String> response = get(
                "https://raw.githubusercontent.com/" + repository + "/HEAD/pom.xml",
                "text/plain");
        if (response.statusCode() != 200) return "";
        Matcher m = PROJECT_VERSION.matcher(response.body());
        return m.find() ? m.group(1).trim() : "";
    }

    private HttpResponse<String> get(String url, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", accept)
                .header("User-Agent", "quarkus-ai-workspace")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
