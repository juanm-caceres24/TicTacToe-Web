package com.tictactoe.solver;

import com.tictactoe.engine.GamePhase;
import com.tictactoe.engine.MoveEncoder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Exports exact optimal policy samples from {@link RetrogradeSolver} into a CSV file
 * compatible with {@code NeuralNetwork/train_app} ({@code 9} player-relative inputs + {@code 41} targets).
 */
public final class DatasetExporter {

    /**
     * Configuration options for exporting the training CSV.
     */
    public record ExportOptions(
        Path outputPath,
        RetrogradeSolver.TargetMode targetMode,
        boolean includeHeader,
        boolean shuffle,
        long seed
    ) {
        public ExportOptions {
            Objects.requireNonNull(outputPath, "outputPath must not be null");
            Objects.requireNonNull(targetMode, "targetMode must not be null");
        }

        public static ExportOptions defaults() {
            return new ExportOptions(
                Path.of("data", "tictactoe_dataset.csv"),
                RetrogradeSolver.TargetMode.SOFT,
                false,
                true,
                42L
            );
        }
    }

    /**
     * Summary of the exported CSV dataset.
     */
    public record ExportSummary(
        Path outputPath,
        int totalSamples,
        int placementSamples,
        int movementSamples,
        int winSamples,
        int drawSamples,
        int lossSamples
    ) {
    }

    private DatasetExporter() {
        // Utility class
    }

    /**
     * Exports all non-terminal optimal policy samples from {@code solver} to CSV.
     */
    public static ExportSummary export(RetrogradeSolver solver, ExportOptions options) throws IOException {
        Objects.requireNonNull(solver, "solver must not be null");
        Objects.requireNonNull(options, "options must not be null");

        List<RetrogradeSolver.TrainingSample> samples = new ArrayList<>(
            solver.buildTrainingSamples(options.targetMode())
        );

        if (options.shuffle()) {
            Collections.shuffle(samples, new Random(options.seed()));
        }

        Path parent = options.outputPath().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int placementCount = 0;
        int movementCount = 0;
        int winCount = 0;
        int drawCount = 0;
        int lossCount = 0;

        try (BufferedWriter writer = Files.newBufferedWriter(options.outputPath(), StandardCharsets.UTF_8)) {
            if (options.includeHeader()) {
                writer.write(buildHeader());
                writer.newLine();
            }

            StringBuilder row = new StringBuilder(256);
            for (RetrogradeSolver.TrainingSample sample : samples) {
                if (sample.phase() == GamePhase.PLACEMENT) {
                    placementCount++;
                } else {
                    movementCount++;
                }
                switch (sample.outcome()) {
                    case WIN -> winCount++;
                    case DRAW -> drawCount++;
                    case LOSS -> lossCount++;
                }

                row.setLength(0);
                double[] input = sample.input();
                for (int i = 0; i < input.length; i++) {
                    if (i > 0) {
                        row.append(',');
                    }
                    appendNumber(row, input[i]);
                }

                double[] target = sample.target();
                for (double v : target) {
                    row.append(',');
                    appendNumber(row, v);
                }

                writer.write(row.toString());
                writer.newLine();
            }
        }

        return new ExportSummary(
            options.outputPath(),
            samples.size(),
            placementCount,
            movementCount,
            winCount,
            drawCount,
            lossCount
        );
    }

    private static String buildHeader() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MoveEncoder.INPUT_SIZE; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('c').append(i);
        }
        for (int a = 0; a < MoveEncoder.OUTPUT_SIZE; a++) {
            sb.append(",a").append(a);
        }
        return sb.toString();
    }

    private static void appendNumber(StringBuilder sb, double value) {
        if (value == 0.0) {
            sb.append('0');
        } else if (value == 1.0) {
            sb.append('1');
        } else if (value == -1.0) {
            sb.append("-1");
        } else {
            sb.append(String.format(Locale.ROOT, "%.6f", value));
        }
    }
}
