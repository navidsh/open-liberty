/*******************************************************************************n * Copyright (c) 2019 IBM Corporation and others.n * All rights reserved. This program and the accompanying materialsn * are made available under the terms of the Eclipse Public License v1.0n * which accompanies this distribution, and is available atn * http://www.eclipse.org/legal/epl-v10.htmln *n * Contributors:n *     IBM Corporation - initial API and implementationn *******************************************************************************/
package com.ibm.ws.install.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ibm.ws.install.InstallException;
import com.ibm.ws.install.internal.ArtifactDownloader;
import com.ibm.ws.install.internal.InstallKernelMap;
import com.ibm.ws.install.internal.InstallLogUtils;
import com.ibm.ws.install.internal.InstallLogUtils.Messages;
import com.ibm.ws.kernel.boot.cmdline.Utils;

/**
 *
 */
public class MavenDirectoryInstall {

    private final InstallKernelMap map;
    private File fromDir;
    private String toExtension;
    private String openLibertyVersion;
    private final Logger logger;
    private boolean tempCleanupRequired;

    private final String TEMP_DIRECTORY = Utils.getInstallDir() + "/tmp/";
    private final String OPEN_LIBERTY_PRODUCT_ID = "io.openliberty";

    public MavenDirectoryInstall(Collection<String> featuresToInstall) throws IOException, InstallException {
        this.logger = InstallLogUtils.getInstallLogger();
        this.tempCleanupRequired = false;

        logger.log(Level.FINE, "The features to install from local maven repository are:" + featuresToInstall);

        getLibertyVersions();
        List<File> jsonPaths = getJsonsFromMavenCentral();
        map = new InstallKernelMap();
        initializeMap(featuresToInstall, jsonPaths);
    }

    /**
     * Initialize a map based install kernel with a local maven directory
     *
     * @param featuresToInstall
     * @param fromDir
     * @throws IOException
     * @throws InstallException
     */
    public MavenDirectoryInstall(Collection<String> featuresToInstall, File fromDir) throws IOException, InstallException {

        this.fromDir = fromDir;
        this.logger = InstallLogUtils.getInstallLogger();
        this.tempCleanupRequired = false;

        logger.log(Level.FINE, "The features to install from local maven repository are:" + featuresToInstall);
        logger.log(Level.FINE, "The local maven repository is: " + fromDir.getAbsolutePath());

        List<File> jsonPaths = getSingleJsonPaths(fromDir);
        map = new InstallKernelMap();
        initializeMap(featuresToInstall, jsonPaths);
        getLibertyVersions();

    }

    public MavenDirectoryInstall(Collection<String> featuresToInstall, File fromDir, String toExtension) throws IOException, InstallException {
        this(featuresToInstall, fromDir);
        this.toExtension = toExtension;
    }

    /**
     * Initialize the Install kernel map.
     *
     * @param featuresToInstall
     * @throws IOException
     */
    private void initializeMap(Collection<String> featuresToInstall, Collection<File> jsonPaths) throws IOException {
        map.put("runtime.install.dir", Utils.getInstallDir());
        map.put("target.user.directory", new File(Utils.getInstallDir(), "tmp"));
        map.put("install.local.esa", true);
        map.put("single.json.file", jsonPaths);
        map.put("features.to.resolve", new ArrayList<>(featuresToInstall));
        map.put("license.accept", true); // TODO: discuss later
        map.get("install.kernel.init.code");

    }

    /**
     * Get the open liberty runtime version.
     *
     * @throws IOException
     * @throws InstallException
     *
     */
    private void getLibertyVersions() throws IOException, InstallException {
        File dir = new File(Utils.getInstallDir(), "lib/versions");
        File[] propertiesFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".properties");
            }
        });

        if (propertiesFiles == null) {
            throw new IOException("Could not find properties files.");
        }

        for (File propertiesFile : propertiesFiles) {
            Properties properties = new Properties();
            try (InputStream input = new FileInputStream(propertiesFile)) {
                properties.load(input);
                String productId = properties.getProperty("com.ibm.websphere.productId");
                String productVersion = properties.getProperty("com.ibm.websphere.productVersion");

                if (productId.equals(OPEN_LIBERTY_PRODUCT_ID)) {
                    openLibertyVersion = productVersion;
                }

            } catch (IOException e) {
                throw new IOException("Could not read the properties file " + propertiesFile.getAbsolutePath());
            }
        }
        if (openLibertyVersion == null) {
            // openliberty.properties file is missing
            throw new InstallException("Could not determine the open liberty runtime version.");
        } else {
            logger.log(Level.FINE, "The Open Liberty runtime version is " + openLibertyVersion);
        }

    }

    /**
     * Resolves and installs the features
     *
     * @throws InstallException
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    public void installFeatures() throws InstallException, IOException {
        Collection<String> resolvedFeatures = (Collection<String>) map.get("action.result");
        logger.log(Level.FINE, "Resolved features: " + resolvedFeatures);
        if (resolvedFeatures == null) {
            throw new InstallException((String) map.get("action.error.message"));
        } else if (resolvedFeatures.isEmpty()) {
            logger.log(Level.FINE, "The list of resolved features is empty.");
            String exceptionMessage = (String) map.get("action.error.message");
            if (exceptionMessage == null) {
                logger.log(Level.INFO, Messages.INSTALL_KERNEL_MESSAGES.getLogMessage("ALREADY_INSTALLED",
                                                                                      map.get("features.to.resolve")));
                return;
            } else if (exceptionMessage.contains("CWWKF1250I")) {
                logger.log(Level.INFO, exceptionMessage);
                return;
            } else {
                throw new InstallException(exceptionMessage);
            }
        }

        Collection<File> artifacts = findEsas(resolvedFeatures, fromDir);

        Collection<String> actionReturnResult = new ArrayList<String>();
        Collection<String> currentReturnResult;

        logger.log(Level.INFO, Messages.INSTALL_KERNEL_MESSAGES.getLogMessage("STATE_STARTING_INSTALL"));
        try {
            for (File esaFile : artifacts) {

                logger.log(Level.FINE, Messages.INSTALL_KERNEL_MESSAGES.getLogMessage("STATE_INSTALLING",
                                                                                      extractFeature(esaFile.getName())));
                map.put("license.accept", true);
                map.put("action.install", esaFile);
                if (toExtension != null) {
                    map.put("to.extension", toExtension);
                    logger.log(Level.FINE, "Installing to extension: " + toExtension);
                }

                Integer ac = (Integer) map.get("action.result");
                logger.log(Level.FINE, "action.result:" + ac);
                logger.log(Level.FINE, "action.error.message:" + map.get("action.error.message"));

                if (map.get("action.error.message") != null) {
                    // error with installation
                    logger.log(Level.FINE, "action.exception.stacktrace: " + map.get("action.error.stacktrace"));
                    String exceptionMessage = (String) map.get("action.error.message");
                    throw new InstallException(exceptionMessage);
                } else if ((currentReturnResult = (Collection<String>) map.get("action.install.result")) != null) {
                    // installation was successful
                    if (!currentReturnResult.isEmpty()) {
                        actionReturnResult.addAll((Collection<String>) map.get("action.install.result"));
                        logger.log(Level.INFO,
                                   (Messages.INSTALL_KERNEL_MESSAGES.getLogMessage("LOG_INSTALLED_FEATURE", String.join(", ", currentReturnResult)).replace("CWWKF1304I: ", ""))); // TODO: come up with new message for
                                                                                                                                                                                                          // successfully
                                                                                                                                                                                                          // installed feature

                    }
                }
            }
            logger.log(Level.INFO, Messages.INSTALL_KERNEL_MESSAGES.getLogMessage("TOOL_INSTALLATION_COMPLETED"));
        } finally {

            cleanUp();
        }
    }

    private Collection<File> findEsas(Collection<String> resolvedFeatures, File rootDir) throws InstallException {
        Collection<File> foundEsas = new HashSet<>();
        Collection<String> featuresClone = new ArrayList<>(resolvedFeatures);

        for (String feature : resolvedFeatures) {
            logger.log(Level.FINE, "Processing feature: " + feature);
            String mavenCoordinate = feature.split(":")[0];
            String featureName = feature.split(":")[1];

            File groupDir = new File(rootDir, mavenCoordinate.replace(".", "/"));
            if (!groupDir.exists()) {
                continue;
            }

            Path featurePath = Paths.get(groupDir.getAbsolutePath().toString(), featureName, openLibertyVersion,
                                         featureName + "-" + openLibertyVersion + ".esa");

            logger.log(Level.FINE, featurePath.toString());
            if (Files.isRegularFile(featurePath)) {
                foundEsas.add(featurePath.toFile());
                featuresClone.remove(feature);
            }

        }
        if (!featuresClone.isEmpty()) {
            logger.log(Level.FINE, "Could not find ESA's for " + resolvedFeatures);
            List<File> downloadedEsas = downloadMissingFeatureEsas((List<String>) resolvedFeatures);

            logger.log(Level.FINE, "Downloaded the following features from maven central:" + downloadedEsas);
            foundEsas.addAll(downloadedEsas);
        }
        return foundEsas;

    }

    /**
     * @throws InstallException
     *
     */
    private List<File> downloadMissingFeatureEsas(List<String> features) throws InstallException {
        map.put("download.artifact.list", features);
        map.put("download.local.dir.location", TEMP_DIRECTORY);
        map.put("download.remote.maven.repo", "http://repo.maven.apache.org/maven2/");
        List<File> result = (List<File>) map.get("download.result");
        this.tempCleanupRequired = true;

        return result;

    }

    /**
     * Extracts the feature name and version from an ESA filepath. Example:
     * extractFeature(appSecurity-3.0-19.0.0.8.esa) returns appSecurity-3.0
     *
     * TODO: extract runtime version
     *
     * @param filename
     * @return
     */
    private String extractFeature(String filename) {
        String[] split = filename.split("-");

        return split[0] + "-" + split[1];

    }

    /**
     * Get the single json files
     *
     * @param filepath
     * @param found
     */
    private List<File> getSingleJsonPaths(File filepath) {
        ArrayList<File> jsonFilesFound = new ArrayList<>();
        if (filepath == null || !filepath.exists()) {
            return jsonFilesFound;
        }
        File[] list = filepath.listFiles();
        for (File f : list) {
            if (f.isDirectory()) {
                jsonFilesFound.addAll(getSingleJsonPaths(f));
            } else if (f.isFile()) {
                if (f.getName().endsWith(".json")) {
                    jsonFilesFound.add(f);
                }
            }
        }

        return jsonFilesFound;

    }

    /**
     * Fetch the open liberty and closed liberty json files from maven central
     *
     * @return
     * @throws InstallException
     */
    private List<File> getJsonsFromMavenCentral() throws InstallException {
        // get open liberty json
        ArtifactDownloader artifactDownloader = new ArtifactDownloader();
        artifactDownloader.synthesizeAndDownload("io.openliberty.features:features:" + openLibertyVersion, "json",
                                                 TEMP_DIRECTORY, "http://repo.maven.apache.org/maven2/");
        artifactDownloader.synthesizeAndDownload("io.openliberty.features:features:" + openLibertyVersion, "json",
                                                 TEMP_DIRECTORY, "http://repo.maven.apache.org/maven2/");
        this.tempCleanupRequired = true;

        logger.log(Level.FINE,
                   "Downloaded the following jsons from maven central:" + artifactDownloader.getDownloadedFiles());
        return artifactDownloader.getDownloadedFiles();

    }

    /**
     * Clean up the temp directory used for storing features downloaded from online
     *
     * @throws IOException
     */
    private void cleanUp() throws IOException {
        File temp = new File(TEMP_DIRECTORY);
        boolean deleted = true;

        if (tempCleanupRequired) {
            deleted = cleanUpHelper(temp);
        }
        if (deleted) {
            logger.log(Level.FINE, Messages.INSTALL_KERNEL_MESSAGES.getLogMessage("MSG_CLEANUP_SUCCESS"));
        } else {
            logger.log(Level.FINE, Messages.INSTALL_KERNEL_MESSAGES.getLogMessage("LOG_CANNOT_CLOSE_OBJECT"));
        }

    }

    private boolean cleanUpHelper(File file) {
        if (file == null || !file.exists())
            return true;

        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                cleanUpHelper(f);
            }

        }
        return file.delete();
    }

}
