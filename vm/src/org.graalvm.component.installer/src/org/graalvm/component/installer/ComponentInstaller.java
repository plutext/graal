/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.component.installer;

import org.graalvm.component.installer.remote.CatalogIterable;
import java.io.File;
import java.io.IOError;
import org.graalvm.component.installer.model.ComponentRegistry;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.graalvm.component.installer.CommonConstants.PATH_COMPONENT_STORAGE;
import org.graalvm.component.installer.commands.AvailableCommand;
import org.graalvm.component.installer.commands.InfoCommand;
import org.graalvm.component.installer.commands.InstallCommand;
import org.graalvm.component.installer.commands.ListInstalledCommand;
import org.graalvm.component.installer.commands.RebuildImageCommand;
import org.graalvm.component.installer.commands.UninstallCommand;
import org.graalvm.component.installer.persist.DirectoryStorage;
import org.graalvm.component.installer.remote.RemoteCatalogDownloader;

/**
 * The launcher.
 */
public final class ComponentInstaller {
    private static final Logger LOG = Logger.getLogger(ComponentInstaller.class.getName());

    public static final String GRAAL_DEFAULT_RELATIVE_PATH = "../../.."; // NOI18N

    private static final Environment SIMPLE_ENV = new Environment("help", Collections.emptyList(), Collections.emptyMap()).enableStacktraces(); // NOI18N

    private String[] mainArguments;
    private String command;
    private InstallerCommand cmdHandler;
    private LinkedList<String> cmdlineParams;
    private List<String> parameters = Collections.emptyList();
    private Path graalHomePath;
    private Path storagePath;
    private String catalogURL;

    static final Map<String, InstallerCommand> commands = new HashMap<>();
    static final Map<String, String> globalOptions = new HashMap<>();

    @SuppressWarnings("deprecation")
    static void initCommands() {
        // not necessary except for tests to cleanup extra items
        commands.clear();
        globalOptions.clear();

        commands.put("install", new InstallCommand()); // NOI18N
        commands.put("uninstall", new UninstallCommand()); // NOI18N
        commands.put("list", new ListInstalledCommand()); // NOI18N
        commands.put("available", new AvailableCommand()); // NOI18N
        commands.put("info", new InfoCommand()); // NOI18N
        commands.put("rebuild-images", new RebuildImageCommand()); // NOI18N

        globalOptions.put(Commands.OPTION_VERBOSE, "");
        globalOptions.put(Commands.OPTION_DEBUG, "");
        globalOptions.put(Commands.OPTION_HELP, "");
        globalOptions.put(Commands.OPTION_CATALOG, "");
        globalOptions.put(Commands.OPTION_FILES, "");
        globalOptions.put(Commands.OPTION_FILES_OLD, "=L");
        globalOptions.put(Commands.OPTION_FOREIGN_CATALOG, "s");
        globalOptions.put(Commands.OPTION_URLS, "");
        globalOptions.put(Commands.OPTION_NO_DOWNLOAD_PROGRESS, "");

        globalOptions.put(Commands.LONG_OPTION_VERBOSE, Commands.OPTION_VERBOSE);
        globalOptions.put(Commands.LONG_OPTION_DEBUG, Commands.OPTION_DEBUG);
        globalOptions.put(Commands.LONG_OPTION_HELP, Commands.OPTION_HELP);
        globalOptions.put(Commands.LONG_OPTION_FILES_OLD, Commands.OPTION_FILES);
        globalOptions.put(Commands.LONG_OPTION_FILES, Commands.OPTION_FILES);
        globalOptions.put(Commands.LONG_OPTION_CATALOG, Commands.OPTION_CATALOG);
        globalOptions.put(Commands.LONG_OPTION_FOREIGN_CATALOG, Commands.OPTION_FOREIGN_CATALOG);
        globalOptions.put(Commands.LONG_OPTION_URLS, Commands.OPTION_URLS);
        globalOptions.put(Commands.LONG_OPTION_NO_DOWNLOAD_PROGRESS, Commands.OPTION_NO_DOWNLOAD_PROGRESS);

        globalOptions.put(Commands.OPTION_AUTO_YES, "");
        globalOptions.put(Commands.LONG_OPTION_AUTO_YES, Commands.OPTION_AUTO_YES);

        globalOptions.put(Commands.OPTION_NON_INTERACTIVE, "");
        globalOptions.put(Commands.LONG_OPTION_NON_INTERACTIVE, Commands.OPTION_NON_INTERACTIVE);
    }

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(
                    "org.graalvm.component.installer.Bundle"); // NOI18N

    private static void forSoftwareChannels(boolean report, Consumer<SoftwareChannel> callback) {
        ServiceLoader<SoftwareChannel> channels = ServiceLoader.load(SoftwareChannel.class);
        for (Iterator<SoftwareChannel> it = channels.iterator(); it.hasNext();) {
            try {
                SoftwareChannel ch = it.next();
                callback.accept(ch);
            } catch (ServiceConfigurationError | Exception ex) {
                if (report) {
                    LOG.log(Level.SEVERE,
                                    MessageFormat.format(BUNDLE.getString("ERROR_SoftwareChannelBroken"), ex.getLocalizedMessage()));
                }
            }
        }
    }

    static {
        initCommands();
        forSoftwareChannels(true, (ch) -> {
            globalOptions.putAll(ch.globalOptions());
        });
    }

    private ComponentInstaller(String[] args) {
        this.mainArguments = args;
    }

    private static void printUsage(CommandInput input, Feedback output) {
        SIMPLE_ENV.error("INFO_InstallerVersion", null, CommonConstants.INSTALLER_VERSION); // NOI18N
        printHelp(input, output);
    }

    private static void printHelp(CommandInput input, Feedback output) {
        StringBuilder extra = new StringBuilder();

        forSoftwareChannels(false, (ch) -> {
            ch.init(input, output);
            String s = ch.globalOptionsHelp();
            if (s != null) {
                extra.append(s);
            }
        });
        String extraS;

        if (extra.length() != 0) {
            extraS = output.l10n("INFO_UsageExtensions", extra.toString());
        } else {
            extraS = ""; // NOI18N
        }

        output.message("INFO_Usage", extraS); // NOI18N
    }

    static void printErr(String messageKey, Object... args) {
        SIMPLE_ENV.message(messageKey, args);
    }

    static RuntimeException err(String messageKey, Object... args) {
        printErr(messageKey, args);
        printHelp(SIMPLE_ENV, SIMPLE_ENV);
        System.exit(1);
        throw new RuntimeException("should not reach here");
    }

    private Environment env;

    private int processCommand() {
        SimpleGetopt go = new SimpleGetopt(globalOptions);
        go.setParameters(cmdlineParams);
        for (String s : commands.keySet()) {
            go.addCommandOptions(s, commands.get(s).supportedOptions());
        }
        go.process();
        cmdHandler = commands.get(go.getCommand());
        Map<String, String> optValues = go.getOptValues();
        if (cmdHandler == null) {
            if (optValues.containsKey(Commands.OPTION_HELP)) {
                // regular Environment cannot be initialized.
                printUsage(SIMPLE_ENV, SIMPLE_ENV);
                return 0;
            }
            err("ERROR_MissingCommand"); // NOI18N
        }
        parameters = go.getPositionalParameters();

        try {
            env = new Environment(command, parameters, optValues);
            finddGraalHome();
            env.setGraalHome(graalHomePath);
            env.setLocalRegistry(new ComponentRegistry(env, new DirectoryStorage(
                            env, storagePath, graalHomePath)));

            int srcCount = 0;
            if (optValues.containsKey(Commands.OPTION_FILES)) {
                srcCount++;
            }
            if (optValues.containsKey(Commands.OPTION_CATALOG)) {
                srcCount++;
            }
            if (optValues.containsKey(Commands.OPTION_FOREIGN_CATALOG)) {
                srcCount++;
            }
            if (optValues.containsKey(Commands.OPTION_URLS)) {
                srcCount++;
            }
            if (srcCount > 1) {
                err("ERROR_MultipleSourcesUnsupported");
            }

            if (env.hasOption(Commands.OPTION_AUTO_YES)) {
                env.setAutoYesEnabled(true);
            }
            if (env.hasOption(Commands.OPTION_NON_INTERACTIVE)) {
                env.setNonInteractive(true);
            }
            if (optValues.containsKey(Commands.OPTION_FILES)) {
                env.setFileIterable(new FileIterable(env, env));
            } else if (optValues.containsKey(Commands.OPTION_URLS)) {
                env.setFileIterable(new DownloadURLIterable(env, env));
            } else {
                catalogURL = optValues.get(Commands.OPTION_FOREIGN_CATALOG);
                RemoteCatalogDownloader downloader = new RemoteCatalogDownloader(
                                env,
                                env,
                                getCatalogURL(env));
                env.setComponentRegistry(downloader::get);
                env.setFileIterable(new CatalogIterable(env, env, downloader));
            }
            cmdHandler.init(env, env.withBundle(cmdHandler.getClass()));
            return cmdHandler.execute();
        } catch (FileAlreadyExistsException ex) {
            env.error("INSTALLER_FileExists", ex, ex.getLocalizedMessage()); // NOI18N
            return 2;
        } catch (NoSuchFileException ex) {
            env.error("INSTALLER_FileDoesNotExist", ex, ex.getLocalizedMessage()); // NOI18N
            return 2;
        } catch (AccessDeniedException ex) {
            env.error("INSTALLER_AccessDenied", ex, ex.getLocalizedMessage());
            return 2;
        } catch (DirectoryNotEmptyException ex) {
            env.error("INSTALLER_DirectoryNotEmpty", ex, ex.getLocalizedMessage()); // NOI18N
            return 2;
        } catch (IOError | IOException ex) {
            env.error("INSTALLER_IOException", ex, ex.getLocalizedMessage()); // NOI18N
            return 2;
        } catch (MetadataException ex) {
            env.error("INSTALLER_InvalidMetadata", ex, ex.getLocalizedMessage()); // NOI18N
            return 3;
        } catch (UserAbortException ex) {
            env.error("ERROR_Aborted", ex, ex.getLocalizedMessage()); // NOI18N
            return 4;
        } catch (InstallerStopException ex) {
            env.error("INSTALLER_Error", ex, ex.getLocalizedMessage()); // NOI18N
            return 3;
        } catch (RuntimeException ex) {
            env.error("INSTALLER_InternalError", ex, ex.getLocalizedMessage()); // NOI18N
            return 3;
        }
    }

    /**
     * Finds Graal Home directory. It is either specified by the GRAAL_HOME system property,
     * environment variable, or the executing JAR's location - in the order of precedence.
     * <p/>
     * The location is sanity checked and the method throws {@link FailedOperationException} if not
     * proper Graal dir.
     * 
     * @return existing Graal home
     */
    Path finddGraalHome() {
        String graalHome = System.getProperty("GRAAL_HOME", System.getenv("GRAAL_HOME")); // NOI18N
        Path graalPath = null;
        if (graalHome != null) {
            graalPath = SystemUtils.fromUserString(graalHome);
        } else {
            URL loc = getClass().getProtectionDomain().getCodeSource().getLocation();
            try {
                File f = new File(loc.toURI());
                if (f != null) {
                    graalPath = f.toPath().resolve(SystemUtils.fromCommonString(GRAAL_DEFAULT_RELATIVE_PATH)).toAbsolutePath();
                }
            } catch (URISyntaxException ex) {
                Logger.getLogger(ComponentInstaller.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
        if (graalPath == null) {
            throw env.failure("ERROR_NoGraalVMDirectory", null);
        }
        if (!Files.isDirectory(graalPath) || !Files.exists(graalPath.resolve(SystemUtils.fileName("release")))) {
            throw env.failure("ERROR_InvalidGraalVMDirectory", null, graalPath);
        }
        if (!Files.isDirectory(storagePath = graalPath.resolve(SystemUtils.fromCommonString(PATH_COMPONENT_STORAGE)))) {
            throw env.failure("ERROR_InvalidGraalVMDirectory", null, graalPath);
        }
        graalHomePath = graalPath;
        return graalPath;
    }

    public void run() {
        if (mainArguments.length < 1) {
            printUsage(SIMPLE_ENV, SIMPLE_ENV);
            System.exit(1);
        }
        try {
            cmdlineParams = new LinkedList<>(Arrays.asList(mainArguments));

            System.exit(processCommand());
        } catch (UserAbortException ex) {
            SIMPLE_ENV.message("ERROR_Aborted", ex.getMessage()); // NOI18N
        } catch (Exception ex) {
            SIMPLE_ENV.error("ERROR_InternalError", ex, ex.getMessage()); // NOI18N
            System.exit(3);
        }
    }

    private String getCatalogURL(Feedback f) {
        String def;
        if (catalogURL != null) {
            def = catalogURL;
        } else {
            String envVar = System.getenv(CommonConstants.ENV_CATALOG_URL);
            if (envVar != null) {
                def = envVar;
            } else {
                String releaseCatalog = env.getLocalRegistry().getGraalCapabilities().get(CommonConstants.RELEASE_CATALOG_KEY);
                if (releaseCatalog == null) {
                    def = f.l10n("Installer_BuiltingCatalogURL"); // NOI18N
                } else {
                    def = releaseCatalog;
                }
            }
        }
        String s = System.getProperty(CommonConstants.SYSPROP_CATALOG_URL, def);
        return s;
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        new ComponentInstaller(args).run();
    }
}
