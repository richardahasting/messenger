package org.hastingtx.meshrelay;

/**
 * Messenger app version, read from the JAR's MANIFEST.MF (Implementation-Version).
 * Falls back to "dev" when running from un-packaged classes (e.g. IDE, unit tests).
 *
 * The version is baked in by the maven-shade-plugin's ManifestResourceTransformer
 * in pom.xml — bump &lt;version&gt; there and rebuild.
 */
public final class Version {

    public static final String VERSION;

    static {
        String v = Version.class.getPackage().getImplementationVersion();
        VERSION = (v != null && !v.isBlank()) ? v : "dev";
    }

    private Version() {}
}
