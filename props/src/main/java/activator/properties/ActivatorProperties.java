/**
 * Copyright (C) 2016 Lightbend <http://www.lightbend.com/>
 */
package activator.properties;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Properties;

// This is a lame-o class that's kinda dirty.  maybe we can clean it up later, but we're using it across two scala versions right now.
public class ActivatorProperties {

  private static Properties loadProperties() {
    Properties props = new Properties();
    java.io.InputStream in = ActivatorProperties.class.getResourceAsStream("activator.properties");
    try {
      props.load(in);
    } catch(java.io.IOException e) { throw new RuntimeException(e); }
    finally { try { in.close(); } catch(java.io.IOException e) { throw new RuntimeException(e); }  }
    return props;
  }

  private static Properties props = loadProperties();

  private static String getPropertyNoOverrides(String name) {
    return props.getProperty(name);
  }

  // trying to avoid activator.activator.home here
  private static String ensureNamespacing(String name) {
    if (name.startsWith("activator."))
      return name;
    else
      return "activator." + name;
  }

  /** Checks the system properties, before the environment, before the hard coded defaults. */
  private static String getPropertyWithOverrides(String name) {
    String value = System.getProperty(name);
    if(value == null) {
      value = System.getenv(ensureNamespacing(name));
    }
    if(value == null) {
      value = System.getenv(ensureNamespacing(name).replace('.', '_').toUpperCase());
    }
    if (value == null) {
      value = getPropertyNoOverrides(name);
    }
    return value;
  }

  /** Looks up a property value, and parses its value as appropriate. */
  private static String lookupOr(String name, String defaultValue) {
    String value = getPropertyWithOverrides(name);
    if(value == null) {
      value = defaultValue;
    }
    return value;
  }

  private static String requirePropertyWithOverrides(String name) {
    String value = getPropertyWithOverrides(name);
    if (value == null)
      throw new RuntimeException("Property '" + name + "' has not been set");
    return value;
  }

  private static String requirePropertyNoOverrides(String name) {
    String value = getPropertyNoOverrides(name);
    if (value == null)
      throw new RuntimeException("Property '" + name + "' has not been set");
    return value;
  }

  public static String TEMPLATE_UUID_PROPERTY_NAME = "template.uuid";
  public static String SCRIPT_NAME = "activator";

  public static String APP_VERSION() {
    return requirePropertyNoOverrides("app.version");
  }

  public static String APP_ABI_VERSION() {
    // TODO - Encode ABI version in metadata...
    return APP_VERSION();
  }

  public static String APP_SCALA_VERSION() {
    return requirePropertyNoOverrides("app.scala.version");
  }

  public static String SBT_DEFAULT_VERSION() {
    return requirePropertyNoOverrides("sbt.default.version");
  }

  private static String cleanUriFileString(String file) {
    return file.replaceAll(" ", "%20");
  }

  private static String uriToFilename(String uriString) {
    String fileString = cleanUriFileString(uriString);
    try {
      java.net.URI uri = new java.net.URI(fileString);
      // Fix UNC path problem on Windows http://www.tomergabel.com/JavaMishandlesUNCPathsOnWindows.aspx
      if (uri.getAuthority() != null) {
        fileString = fileString.replace("file://", "file:/");
        uri = new java.net.URI(fileString);
      }
      return new java.io.File(uri).getAbsolutePath();
    } catch(java.net.URISyntaxException ex) {
      // TODO - fix this error handling to not suck.
      throw new RuntimeException("BAD URI: " + fileString);
    } catch(java.lang.IllegalArgumentException ex) {
      throw new RuntimeException("BAD URI: " + fileString + "\n", ex);
    }
  }

  /** Returns the distribution home directory (or local project) as a URI string. */
  public static String ACTIVATOR_HOME_FILENAME() {
    // TODO - We should probably remove all spaces and URI-ify the string first.
    return uriToFilename("file://" + ACTIVATOR_HOME());
  }

  /** Returns the distribution home directory (or local project) as a URI string. */
  public static String ACTIVATOR_HOME() {
    return requirePropertyWithOverrides("activator.home");
  }

  public static String GLOBAL_USER_HOME() {
    return requirePropertyWithOverrides("user.home");
  }

  // If you need these directories, consider keeping them private
  // and instead just exporting a value for the final filename
  // you would construct, like ACTIVATOR_USER_CONFIG_FILE below.

  private static String ACTIVATOR_UNVERSIONED_USER_HOME() {
    return lookupOr("activator.user.home", GLOBAL_USER_HOME() + "/.activator");
  }

  private static String ACTIVATOR_VERSIONED_USER_HOME() {
    return ACTIVATOR_UNVERSIONED_USER_HOME() + "/" + APP_ABI_VERSION();
  }

  private static String ACTIVATOR_USER_CONFIG_HOME() {
    return ACTIVATOR_UNVERSIONED_USER_HOME() + "/"
        + requirePropertyWithOverrides("app.config.version");
  }

  private static String ACTIVATOR_PREVIOUS_USER_CONFIG_HOME() {
    return ACTIVATOR_UNVERSIONED_USER_HOME() + "/"
        + requirePropertyWithOverrides("app.config.previousVersion");
  }

  public static String ACTIVATOR_USER_CONFIG_FILE() {
    return ACTIVATOR_USER_CONFIG_HOME() + "/config.json";
  }

  public static String ACTIVATOR_PREVIOUS_USER_CONFIG_FILE() {
    return ACTIVATOR_PREVIOUS_USER_CONFIG_HOME() + "/config.json";
  }

  public static String ACTIVATOR_VERSION_FILE() {
    // this filename is also constructed in the launcher config file, so keep
    // in sync with that...
    return ACTIVATOR_UNVERSIONED_USER_HOME() + "/version-" + ACTIVATOR_LAUNCHER_GENERATION()
        + ".properties";
  }

  // where to get the latest version
  public static String ACTIVATOR_LATEST_URL() {
    return lookupOr("activator.latest.url", "https://lightbend.com/activator/latest");
  }

  // We will consume latest versions only if they match this.
  // So we would bump this if we want to require people to
  // update their launcher.
  public static int ACTIVATOR_LAUNCHER_GENERATION() {
    return Integer.parseInt(requirePropertyWithOverrides("activator.launcher.generation"));
  }

  public static String ACTIVATOR_TEMPLATE_CACHE() {
    return lookupOr("activator.template.cache", ACTIVATOR_VERSIONED_USER_HOME() + "/templates");
  }

  public static String ACTIVATOR_TEMPLATE_LOCAL_REPO() {
    String defaultValue = ACTIVATOR_HOME_FILENAME();
    if(defaultValue != null) {
      defaultValue = defaultValue + "/templates";
    }
    return lookupOr("activator.template.localrepo", defaultValue);
  }

  private static String ACTIVATOR_LAUNCHER_JAR_MATCHING_VERSION_NAME() {
    String version = APP_VERSION();
    if(version != null) {
      // TODO - synch this with build in some better fashion!
      return SCRIPT_NAME+"-launch-"+version+".jar";
    }
    return null;
  }

  private static FilenameFilter activatorLauncherFilter() {
    return new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.startsWith("activator-launch-") && name.endsWith(".jar");
      }
    };
  }

  // this class is a trick to get a lazy singleton
  private static class LauncherJarHolder {
    private static File doFindLauncherJar(String jarname, File home) {
      File jar = new File(home, jarname);
      if (jar.exists()) {
        return jar;
      } else {
        File[] matches = home.listFiles(activatorLauncherFilter());
        if (matches != null && matches.length > 0) {
          return matches[0];
        } else {
          // this really shouldn't happen, so go ahead and spam stderr
          File libexec = new File(home,"libexec");
          File[] matchesLibexec = libexec.listFiles(activatorLauncherFilter());
          if (matchesLibexec != null && matchesLibexec.length > 0) {
            return matchesLibexec[0];
          } else {
            System.err.println("No activator-launch-*.jar in " + libexec);
            return null;
          }
        }
      }
    }

    private static File findLauncherJar(File home) {
      String jarname = ACTIVATOR_LAUNCHER_JAR_MATCHING_VERSION_NAME();
      if(jarname != null) {
        // The Activator homedir may be from an older version of
        // activator due to auto-updates.
        // We first look for a filename that matches our own version,
        // and if that fails, we glob for any activator-launch-*.jar
        // in the activator home.
        return doFindLauncherJar(jarname,home);
      }
      return null;
    }

    public static File launcherJar(String prefix) {
      String value = ACTIVATOR_HOME_FILENAME();
      if(value != null) {
        if (prefix != null && !prefix.isEmpty()) {
          return findLauncherJar(new File(value, prefix));
        } else {
          return findLauncherJar(new File(value));
        }
      }
      return null;
    }
  }

  public static String ACTIVATOR_LAUNCHER_JAR_NAME(String prefix) {
    File lj = LauncherJarHolder.launcherJar(prefix);
    if (lj != null)
      return lj.getName();
    else
      return null;
  }

  public static String ACTIVATOR_LAUNCHER_JAR(String prefix) {
    File lj = LauncherJarHolder.launcherJar(prefix);
    if (lj != null)
      return lj.getPath();
    else
      return null;
  }

  public static String ACTIVATOR_LAUNCHER_BAT(String prefix) {
    String value = ACTIVATOR_HOME_FILENAME();
    if(value != null) {
      value = value+prefix+SCRIPT_NAME+".bat";
    }
    return value;
  }
  public static String ACTIVATOR_LAUNCHER_BASH(String prefix) {
    String value = ACTIVATOR_HOME_FILENAME();
    if(value != null) {
      value = value+prefix+SCRIPT_NAME;
    }
    return value;
  }

  public static java.io.File ACTIVATOR_LOCK_FILE() {
    return new java.io.File(ACTIVATOR_VERSIONED_USER_HOME() + "/.lock");
  }

  public static java.io.File ACTIVATOR_PID_FILE() {
    return new java.io.File(ACTIVATOR_VERSIONED_USER_HOME() + "/.currentpid");
  }

  public static boolean ACTIVATOR_CHECK_FOR_UPDATES() {
    try {
      return Boolean.parseBoolean(lookupOr("activator.checkForUpdates", "true"));
    } catch (Exception e) {
      System.err.println("Warning: bad value for activator.checkForUpdates: " + e.getMessage());
      return true;
    }
  }

  public static boolean ACTIVATOR_PROXY_DEBUG() {
    try {
      return Boolean.parseBoolean(lookupOr("activator.proxyDebug", "false"));
    } catch(Exception e){
      System.err.println("Warning: bad value for activator.proxyDebug: " + e.getMessage());
      return false;
    }
  }
}
