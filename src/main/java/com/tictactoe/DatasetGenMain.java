package com.tictactoe;

import com.tictactoe.engine.GameEngine;
import com.tictactoe.solver.DatasetExporter;
import com.tictactoe.solver.RetrogradeSolver;
import com.tictactoe.solver.StateEvaluation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * CLI utility that solves Mobile Tic-Tac-Toe exactly via Retrograde Analysis
 * and exports a {@code 9 -> 41} supervised training CSV for {@code NeuralNetwork}.
 */
public final class DatasetGenMain {

    /**
     * Parsed CLI options for {@link DatasetGenMain}.
     */
    public record DatasetCliArgs(
        boolean helpRequested,
        DatasetExporter.ExportOptions exportOptions
    ) {
        public static DatasetCliArgs parse(String[] args) {
            Objects.requireNonNull(args, "args must not be null");

            boolean help = false;
            DatasetExporter.ExportOptions defaults = DatasetExporter.ExportOptions.defaults();
            Path outputPath = defaults.outputPath();
            RetrogradeSolver.TargetMode targetMode = defaults.targetMode();
            boolean includeHeader = defaults.includeHeader();
            boolean shuffle = defaults.shuffle();
            long seed = defaults.seed();

            for (int i = 0; i < args.length; i++) {
                String flag = args[i];
                switch (flag) {
                    case "-h", "--help" -> help = true;
                    case "--output" -> {
                        String val = requireValue(args, i, flag).trim();
                        if (val.isEmpty()) {
                            throw new IllegalArgumentException("Option --output must not be blank");
                        }
                        outputPath = Path.of(val);
                        i++;
                    }
                    case "--target-mode" -> {
                        String val = requireValue(args, i, flag).trim().toLowerCase(Locale.ROOT);
                        targetMode = switch (val) {
                            case "soft" -> RetrogradeSolver.TargetMode.SOFT;
                            case "one-hot", "onehot" -> RetrogradeSolver.TargetMode.ONE_HOT;
                            default -> throw new IllegalArgumentException(
                                "Invalid --target-mode '" + val + "'. Expected 'soft' or 'one-hot'."
                            );
                        };
                        i++;
                    }
                    case "--header" -> includeHeader = true;
                    case "--no-shuffle" -> shuffle = false;
                    case "--seed" -> {
                        String val = requireValue(args, i, flag).trim();
                        try {
                            seed = Long.parseLong(val);
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Invalid numeric value for --seed: '" + val + "'", e);
                        }
                        i++;
                    }
                    default -> throw new IllegalArgumentException(
                        "Unknown argument '" + flag + "'. Use --help for usage."
                    );
                }
            }

            return new DatasetCliArgs(
                help,
                new DatasetExporter.ExportOptions(outputPath, targetMode, includeHeader, shuffle, seed)
            );
        }

        public static String usageText() {
            return """
                Usage: java -cp target/classes com.tictactoe.DatasetGenMain [options]

                Solves Mobile Tic-Tac-Toe via exact Retrograde Analysis and exports a
                9-input, 41-output CSV dataset ready for NeuralNetwork/train_app.

                Options:
                  --output <path>             Output CSV path (default: data/tictactoe_dataset.csv)
                  --target-mode <soft|one-hot> Target distribution over tied optimal moves (default: soft)
                  --header                    Include CSV header row (c0..c8,a0..a40)
                  --no-shuffle                Keep canonical state order instead of shuffling
                  --seed <long>               Random seed for deterministic shuffling (default: 42)
                  -h, --help                  Show this help message and exit
                """;
        }

        private static String requireValue(String[] args, int flagIndex, String flag) {
            if (flagIndex + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for option " + flag);
            }
            return args[flagIndex + 1];
        }
    }

    private DatasetGenMain() {
        // Entry point class
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Testable entrypoint returning an exit code without invoking {@link System#exit(int)}.
     */
    public static int run(String[] args) {
        DatasetCliArgs parsed;
        try {
            parsed = DatasetCliArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(DatasetCliArgs.usageText());
            return 1;
        }

        if (parsed.helpRequested()) {
            System.out.print(DatasetCliArgs.usageText());
            return 0;
        }

        long startNanos = System.nanoTime();
        RetrogradeSolver solver = RetrogradeSolver.solve();
        long solveMs = (System.nanoTime() - startNanos) / 1_000_000L;

        StateEvaluation initialEval = solver.evaluate(new GameEngine());

        DatasetExporter.ExportSummary summary;
        try {
            summary = DatasetExporter.export(solver, parsed.exportOptions());
        } catch (IOException e) {
            System.err.println("Error writing dataset: " + e.getMessage());
            return 1;
        }

        System.out.println("==========================================================");
        System.out.println("  Mobile Tic-Tac-Toe Retrograde Dataset Generator");
        System.out.println("----------------------------------------------------------");
        System.out.println("  Solve time         : " + solveMs + " ms");
        System.out.println("  Reachable states   : " + solver.reachableStateCount());
        System.out.println("  Exported samples   : " + summary.totalSamples()
            + " (Placement: " + summary.placementSamples()
            + ", Movement: " + summary.movementSamples() + ")");
        System.out.println("  Outcomes (W/D/L)   : " + summary.winSamples()
            + " / " + summary.drawSamples()
            + " / " + summary.lossSamples());
        System.out.println("  Empty board eval   : " + initialEval.outcome()
            + " (DTM=" + initialEval.distanceToMate()
            + ", optimal moves=" + initialEval.optimalMoves() + ")");
        System.out.println("  Target mode        : " + parsed.exportOptions().targetMode());
        System.out.println("  Output CSV         : " + summary.outputPath());
        System.out.println("==========================================================");
        return 0;
    }
}
