/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.launcher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.graalvm.options.OptionCategory;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Language;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.PolyglotException.StackFrame;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

public final class PolyglotLauncher extends Launcher {

    private String mainLanguage = null;
    private boolean verbose = false;
    private boolean version = false;
    private boolean shell = false;

    @Override
    protected void printHelp(OptionCategory maxCategory) {
        Engine engine = Engine.create();
        // @formatter:off
        printVersion(engine);
        System.out.println();
        System.out.println("Usage: polyglot [OPTION]... [FILE] [ARGS]...");
        List<Language> languages = sortedLanguages(engine);
        System.out.print("Available languages: ");
        String sep = "";
        for (Language language : languages) {
            System.out.print(sep);
            System.out.print(language.getId());
            sep = ", ";
        }
        System.out.println();
        System.out.println("Basic Options:");
        printOption("--language <lang>",      "Specifies the main language.");
        printOption("--file [<lang>:]FILE",   "Additional file to execute.");
        printOption("--eval [<lang>:]CODE",   "Evaluates code snippets, for example, '--eval js:42'.");
        printOption("--shell",                "Start a multi language shell.");
        printOption("--verbose",              "Enable verbose stack trace for internal errors.");
        // @formatter:on
    }

    @Override
    protected void collectArguments(Set<String> args) {
        args.addAll(Arrays.asList(
                        "--language",
                        "--file [<lang>:]FILE",
                        "--eval [<lang>:]CODE",
                        "--shell"));
    }

    @Override
    protected void printVersion() {
        printVersion(Engine.create());
    }

    protected static void printVersion(Engine engine) {
        String engineImplementationName = engine.getImplementationName();
        if (isAOT()) {
            engineImplementationName += " Native";
        }
        System.out.println(String.format("%s polyglot launcher %s", engineImplementationName, engine.getVersion()));
    }

    /**
     * Parse arguments when running the bin/polyglot launcher directly.
     */
    private List<String> parsePolyglotLauncherOptions(Deque<String> arguments, List<Script> scripts) {
        List<String> unrecognizedArgs = new ArrayList<>();

        while (!arguments.isEmpty()) {
            String arg = arguments.removeFirst();

            if (arg.equals("--eval") || arg.equals("--file")) {
                String value = getNextArgument(arguments, arg);
                int index = value.indexOf(":");
                String languageId = null;
                String script;
                if (index != -1) {
                    languageId = value.substring(0, index);
                    script = value.substring(index + 1);
                } else {
                    script = value;
                }
                if (arg.equals("--eval")) {
                    scripts.add(new EvalScript(languageId, script));
                } else {
                    scripts.add(new FileScript(languageId, script, false));
                }
            } else if (arg.equals("--")) {
                break;
            } else if (!arg.startsWith("-")) {
                scripts.add(new FileScript(mainLanguage, arg, true));
                break;
            } else if (arg.equals("--version")) {
                version = true;
            } else if (arg.equals("--shell")) {
                shell = true;
            } else if (arg.equals("--verbose")) {
                verbose = true;
            } else if (arg.equals("--language")) {
                mainLanguage = getNextArgument(arguments, arg);
            } else {
                unrecognizedArgs.add(arg);
            }
        }

        return unrecognizedArgs;
    }

    private String getNextArgument(Deque<String> arguments, String option) {
        if (arguments.isEmpty()) {
            throw abort(option + " expects an argument");
        }
        return arguments.removeFirst();
    }

    private void launch(String[] args) {
        List<String> argumentsList = new ArrayList<>(Arrays.asList(args));
        if (isAOT()) {
            nativeAccess.maybeExec(argumentsList, true, Collections.emptyMap(), VMType.Native);
        }

        final Deque<String> arguments = new ArrayDeque<>(argumentsList);
        if (!arguments.isEmpty() && arguments.getFirst().equals("--use-launcher")) {
            // We are called from another launcher which used --polyglot
            String launcherName = getNextArgument(arguments, "--use-launcher");
            switchToLauncher(launcherName, new HashMap<>(), new ArrayList<>(arguments));
            return;
        }

        List<Script> scripts = new ArrayList<>();
        List<String> unrecognizedArgs = parsePolyglotLauncherOptions(arguments, scripts);

        Map<String, String> polyglotOptions = new HashMap<>();
        parsePolyglotOptions(null, polyglotOptions, unrecognizedArgs);

        String[] programArgs = arguments.toArray(new String[0]);

        if (runPolyglotAction()) {
            return;
        }
        argumentsProcessingDone();

        final Context.Builder contextBuilder = Context.newBuilder().options(polyglotOptions);

        contextBuilder.allowAllAccess(true);
        setupLogHandler(contextBuilder);

        if (version) {
            printVersion(Engine.newBuilder().options(polyglotOptions).build());
            throw exit();
        }

        if (shell) {
            runShell(contextBuilder);
        } else if (scripts.size() == 0) {
            throw abort("No files specified. Use --help for usage instructions.");
        } else {
            runScripts(scripts, contextBuilder, programArgs);
        }
    }

    static final Map<String, Class<AbstractLanguageLauncher>> AOT_LAUNCHER_CLASSES;

    static {
        if (IS_AOT) {
            Stream<String> classNames = Pattern.compile(",").splitAsStream(System.getProperty("com.oracle.graalvm.launcher.launcherclasses"));
            AOT_LAUNCHER_CLASSES = classNames.map(PolyglotLauncher::getLauncherClass).collect(Collectors.toMap(Class::getName, Function.identity()));
        } else {
            AOT_LAUNCHER_CLASSES = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<AbstractLanguageLauncher> getLauncherClass(String launcherName) {
        try {
            Class<?> launcherClass = Class.forName(launcherName);
            if (!AbstractLanguageLauncher.class.isAssignableFrom(launcherClass)) {
                throw new RuntimeException("Launcher class " + launcherName + " does not extend AbstractLanguageLauncher");
            }
            return (Class<AbstractLanguageLauncher>) launcherClass;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Launcher class " + launcherName + " does not exist", e);
        }
    }

    private void switchToLauncher(String launcherName, Map<String, String> options, List<String> args) {
        Class<AbstractLanguageLauncher> launcherClass;
        if (isAOT()) {
            launcherClass = AOT_LAUNCHER_CLASSES.get(launcherName);
            if (launcherClass == null) {
                throw abort("Could not find class '" + launcherName + "'.\nYou might need to rebuild the polyglot launcher with 'gu rebuild-images polyglot'.");
            }
        } else {
            launcherClass = getLauncherClass(launcherName);
        }
        try {
            AbstractLanguageLauncher launcher = launcherClass.getDeclaredConstructor().newInstance();
            launcher.setPolyglot(true);
            launcher.launch(args, options, false);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate launcher class " + launcherName, e);
        }
    }

    private void checkLanguage(String language, Engine engine) {
        if (language == null) {
            return;
        }
        if (!engine.getLanguages().containsKey(language)) {
            throw abort("Language '" + language + "' not found");
        }
    }

    private void runScripts(List<Script> scripts, Context.Builder contextBuilder, String[] programArgs) {
        Script mainScript = scripts.get(scripts.size() - 1);
        try (Context context = contextBuilder.arguments(mainScript.getLanguage(), programArgs).build()) {
            Engine engine = context.getEngine();
            checkLanguage(mainLanguage, engine);
            for (Script script : scripts) {
                checkLanguage(script.languageId, engine);
            }
            for (Script script : scripts) {
                try {
                    Value result = context.eval(script.getSource());
                    if (script.isPrintResult()) {
                        System.out.println(result);
                    }
                } catch (PolyglotException e) {
                    throw abort(e);
                } catch (IOException e) {
                    throw abort(e);
                } catch (Throwable t) {
                    throw abort(t);
                }
            }
        }
    }

    AbortException abort(PolyglotException e) {
        if (e.isInternalError()) {
            System.err.println("Internal error occurred: " + e.toString());
            if (verbose) {
                e.printStackTrace(System.err);
            } else {
                System.err.println("Run with --verbose to see the full stack trace.");
            }
            throw exit(1);
        } else if (e.isExit()) {
            throw exit(e.getExitStatus());
        } else if (e.isSyntaxError()) {
            throw abort(e.getMessage(), 1);
        } else {
            List<StackFrame> trace = new ArrayList<>();
            for (StackFrame stackFrame : e.getPolyglotStackTrace()) {
                trace.add(stackFrame);
            }
            // remove trailing host frames
            for (int i = trace.size() - 1; i >= 0; i--) {
                if (trace.get(i).isHostFrame()) {
                    trace.remove(i);
                } else {
                    break;
                }
            }
            if (e.isHostException()) {
                System.err.println(e.asHostException().toString());
            } else {
                System.err.println(e.getMessage());
            }
            for (StackFrame stackFrame : trace) {
                System.err.print("        at ");
                System.err.println(stackFrame.toString());
            }
            throw exit(1);
        }
    }

    private void runShell(Context.Builder contextBuilder) {
        try (Context context = contextBuilder.build()) {
            MultiLanguageShell polyglotShell = new MultiLanguageShell(context, System.in, System.out, mainLanguage);
            throw exit(polyglotShell.readEvalPrint());
        } catch (IOException e) {
            throw abort(e);
        }
    }

    public static void main(String[] args) {
        try {
            PolyglotLauncher launcher = new PolyglotLauncher();
            try {
                launcher.launch(args);
            } catch (AbortException e) {
                throw e;
            } catch (PolyglotException e) {
                handlePolyglotException(e);
            } catch (Throwable t) {
                throw launcher.abort(t);
            }
        } catch (AbortException e) {
            handleAbortException(e);
        }
    }

    private abstract class Script {
        final String languageId;

        Script(String languageId) {
            this.languageId = languageId;
        }

        final String getLanguage() {
            String language = languageId;
            if (language == null) {
                language = findLanguage();
            }
            if (language == null) {
                if (mainLanguage != null) {
                    return mainLanguage;
                }
            }
            if (language == null) {
                throw abort(String.format("Can not determine language for '%s' %s", this, this.getLanguageSpecifierHelp()));
            }
            return language;
        }

        protected String findLanguage() {
            return null;
        }

        public abstract boolean isPrintResult();

        public abstract Source getSource() throws IOException;

        protected abstract String getLanguageSpecifierHelp();
    }

    private class FileScript extends Script {
        private final boolean main;
        final String file;
        private Source source;

        FileScript(String languageId, String file, boolean main) {
            super(languageId);
            this.file = file;
            this.main = main;
        }

        @Override
        public Source getSource() throws IOException {
            if (source == null) {
                source = Source.newBuilder(getLanguage(), new File(file)).build();
            }
            return source;
        }

        @Override
        protected String findLanguage() {
            try {
                return Source.findLanguage(new File(file));
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return file;
        }

        @Override
        protected String getLanguageSpecifierHelp() {
            if (main) {
                return "use the --language option";
            } else {
                return "specify the language using --file <language>:<file>. ";
            }
        }

        @Override
        public boolean isPrintResult() {
            return false;
        }
    }

    private class EvalScript extends Script {
        final String script;

        EvalScript(String languageId, String script) {
            super(languageId);
            this.script = script;
        }

        @Override
        public Source getSource() {
            return Source.create(getLanguage(), script);
        }

        @Override
        public String toString() {
            return script;
        }

        @Override
        protected String getLanguageSpecifierHelp() {
            return "specify the language using --eval <language>:<source>.";
        }

        @Override
        public boolean isPrintResult() {
            return true;
        }
    }
}
