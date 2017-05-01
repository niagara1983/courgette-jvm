package courgette.runtime;

import cucumber.api.CucumberOptions;
import cucumber.runtime.model.CucumberFeature;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Arrays.asList;
import static java.util.Arrays.copyOf;

public class CourgetteRuntimeOptions {
    private final CourgetteProperties courgetteProperties;
    private final CucumberFeature cucumberFeature;
    private final CucumberOptions cucumberOptions;

    private List<String> runtimeOptions = new ArrayList<>();
    private String rerunFile;

    private final String TMP_DIR = System.getProperty("java.io.tmpdir");
    private final String DEFAULT_RERUN_PLUGIN = "target/courgette-rerun.txt";

    public CourgetteRuntimeOptions(CourgetteProperties courgetteProperties, CucumberFeature cucumberFeature) {
        this.courgetteProperties = courgetteProperties;
        this.cucumberFeature = cucumberFeature;
        this.cucumberOptions = courgetteProperties.getCourgetteOptions().cucumberOptions();

        createRuntimeOptions(cucumberOptions, cucumberFeature).entrySet().forEach(entry -> runtimeOptions.addAll(entry.getValue()));
    }

    public CourgetteRuntimeOptions(CourgetteProperties courgetteProperties) {
        this.courgetteProperties = courgetteProperties;
        this.cucumberFeature = null;
        this.cucumberOptions = courgetteProperties.getCourgetteOptions().cucumberOptions();

        createRuntimeOptions(cucumberOptions, null).entrySet().forEach(entry -> runtimeOptions.addAll(entry.getValue()));
    }

    public String[] getRuntimeOptions() {
        return copyOf(runtimeOptions.toArray(), runtimeOptions.size(), String[].class);
    }

    public String[] getRerunRuntimeOptions(String rerunFeatureScenario) {
        Map<String, List<String>> runtimeOptionMap = createRuntimeOptions(cucumberOptions, cucumberFeature);

        List<String> plugins = runtimeOptionMap.getOrDefault("--plugin", new ArrayList<>());

        final int rerunPluginIndex = plugins.indexOf(plugins.stream().filter(p -> p.startsWith("rerun")).findFirst().orElse(null));
        if (rerunPluginIndex > 0) {
            plugins.remove(rerunPluginIndex);
            plugins.remove(rerunPluginIndex - 1);
        }

        runtimeOptionMap.put("--plugin", plugins);

        runtimeOptionMap.remove("--tags");
        runtimeOptionMap.put(null, new ArrayList<String>() {
            {
                add(rerunFeatureScenario);
            }
        });

        final List<String> runtimeOptions = new ArrayList<>();
        runtimeOptionMap.entrySet().forEach(entry -> runtimeOptions.addAll(entry.getValue()));

        return copyOf(runtimeOptions.toArray(), runtimeOptions.size(), String[].class);
    }

    public String getRerunFile() {
        return rerunFile;
    }

    public String getCucumberRerunFile() {
        final String cucumberRerunFile = cucumberRerunPlugin.apply(courgetteProperties);

        if (cucumberRerunFile == null) {
            return getRerunFile();
        }
        return cucumberRerunFile;
    }

    public List<String> getReportJsFiles() {
        final List<String> reportFiles = new ArrayList<>();

        runtimeOptions.forEach(option -> {
            if (option != null && isReportPlugin.test(option)) {
                String reportFile = option.substring(option.indexOf(":") + 1);

                if (reportFile.endsWith(".html")) {
                    reportFile = reportFile + "/report.js";
                }
                reportFiles.add(reportFile);
            }
        });
        return reportFiles;
    }

    private Map<String, List<String>> createRuntimeOptions(CucumberOptions cucumberOptions, CucumberFeature feature) {
        final Map<String, List<String>> runtimeOptions = new HashMap<>();

        runtimeOptions.put("--glue", optionParser.apply("--glue", cucumberOptions.glue()));
        runtimeOptions.put("--tags", optionParser.apply("--tags", cucumberOptions.tags()));
        runtimeOptions.put("--plugin", optionParser.apply("--plugin", parsePlugins(cucumberOptions.plugin())));
        runtimeOptions.put("--format", optionParser.apply("--format", parsePlugins(cucumberOptions.format())));
        runtimeOptions.put("--name", optionParser.apply("--name", cucumberOptions.name()));
        runtimeOptions.put("--junit", optionParser.apply("--junit", cucumberOptions.junit()));
        runtimeOptions.put("--snippets", optionParser.apply("--snippets", cucumberOptions.snippets()));
        runtimeOptions.put(null, feature == null ? optionParser.apply(null, cucumberOptions.features()) : featureParser.apply(feature.getPath(), cucumberOptions.features()));
        runtimeOptions.values().removeIf(Objects::isNull);

        return runtimeOptions;
    }

    private String getMultiThreadRerunFile() {
        return TMP_DIR + courgetteProperties.getSessionId() + "_rerun_" + cucumberFeature.getGherkinFeature().getId() + ".txt";
    }

    private String getMultiThreadReportFile() {
        return TMP_DIR + courgetteProperties.getSessionId() + "_thread_report_" + cucumberFeature.getGherkinFeature().getId();
    }

    private Function<CourgetteProperties, String> cucumberRerunPlugin = (courgetteProperties) -> {
        final String rerunPlugin = Arrays.stream(courgetteProperties.getCourgetteOptions()
                .cucumberOptions()
                .plugin()).filter(p -> p.startsWith("rerun")).findFirst().orElse(null);

        if (rerunPlugin != null) {
            return rerunPlugin.substring(rerunPlugin.indexOf(":") + 1);
        }
        return null;
    };

    private final Predicate<String> isReportPlugin = (plugin) -> plugin.startsWith("html:") || plugin.startsWith("json:");

    private String[] parsePlugins(String[] plugins) {
        List<String> pluginList = new ArrayList<>();

        if (plugins.length > 0) {
            asList(plugins).forEach(plugin -> {
                if (isReportPlugin.test(plugin)) {
                    if (cucumberFeature != null) {
                        pluginList.add(plugin);

                        String extension = plugin.substring(0, plugin.indexOf(":"));

                        if (!extension.equals("")) {
                            final String reportPath = String.format("%s:%s.%s", extension, getMultiThreadReportFile(), extension);
                            pluginList.add(reportPath);
                        }
                    } else {
                        pluginList.add(plugin);
                    }
                }
            });

            Predicate<List<String>> alreadyAddedRerunPlugin = (addedPlugins) -> addedPlugins.stream().anyMatch(p -> p.startsWith("rerun:"));

            if (!alreadyAddedRerunPlugin.test(pluginList)) {
                if (cucumberFeature != null) {
                    rerunFile = getMultiThreadRerunFile();
                } else {
                    final String cucumberRerunFile = cucumberRerunPlugin.apply(courgetteProperties);
                    rerunFile = cucumberRerunFile != null ? cucumberRerunFile : DEFAULT_RERUN_PLUGIN;
                }
                pluginList.add("rerun:" + rerunFile);
            }
        }
        return copyOf(pluginList.toArray(), pluginList.size(), String[].class);
    }

    private BiFunction<String, Object, List<String>> optionParser = (name, options) -> {
        final List<String> runOptions = new ArrayList<>();

        final Boolean isStringArray = options instanceof String[];

        if (options == null || (isStringArray && ((String[]) options).length == 0)) {
            return runOptions;
        }

        if (isStringArray) {
            final String[] optionArray = (String[]) options;

            asList(asList(optionArray).toString().split(","))
                    .forEach(value -> {
                        runOptions.add(name);
                        runOptions.add(value.trim().replace("[", "").replace("]", ""));
                    });
        } else {
            runOptions.add(name);
            runOptions.add(options.toString());
        }

        return runOptions;
    };

    private BiFunction<String, String[], List<String>> featureParser = (featurePath, features) -> {
        if (featurePath != null) {
            final String fullFeaturePath = Arrays.stream(features)
                    .filter(f -> f.indexOf(featurePath.substring(0, featurePath.indexOf("/"))) > 0)
                    .findFirst().orElse("") + featurePath.substring(featurePath.indexOf("/"));

            return new ArrayList<String>() {
                {
                    add(fullFeaturePath);
                }
            };
        }
        return null;
    };
}