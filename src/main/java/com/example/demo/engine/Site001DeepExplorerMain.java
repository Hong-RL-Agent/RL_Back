package com.example.demo.engine;

import java.nio.file.Path;

public class Site001DeepExplorerMain {
    public static void main(String[] args) {
        String targetUrl = args.length > 0 ? args[0] : "http://localhost:9220/";
        int maxTicks = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        boolean headless = args.length > 2 && Boolean.parseBoolean(args[2]);
        Path outputDirectory = args.length > 3 ? Path.of(args[3]) : Path.of("exploration-results-site001-deep");

        Site001DeepExplorer explorer = new Site001DeepExplorer(headless, outputDirectory);
        explorer.explore(targetUrl, maxTicks);
    }
}
