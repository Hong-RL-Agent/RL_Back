package com.example.demo.engine;

import java.nio.file.Path;

public class ExplorerMain {

    public static void main(String[] args) {
        String targetUrl = args.length > 0 ? args[0] : "http://localhost:3000/";
        int maxSteps = args.length > 1 ? Integer.parseInt(args[1]) : 15;
        boolean headless = args.length > 2 && Boolean.parseBoolean(args[2]);
        Path outputDirectory = args.length > 3 ? Path.of(args[3]) : Path.of("exploration-results");

        AutonomousExplorer explorer = new AutonomousExplorer(headless, outputDirectory);
        explorer.explore(targetUrl, maxSteps);
    }
}
