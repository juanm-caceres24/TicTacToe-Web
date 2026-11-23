package com.tictactoe.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tictactoe.DatasetGenMain;
import com.tictactoe.engine.BoardTopology;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.MoveEncoder;
import com.tictactoe.engine.Player;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetExporterAndCliTest {

    @Test
    void exportsValidFiftyColumnSoftAndOneHotDatasets(@TempDir Path tempDir) throws IOException {
        RetrogradeSolver solver = RetrogradeSolver.solve();

        // 1. Soft target distribution without header
        Path softCsv = tempDir.resolve("soft.csv");
        DatasetExporter.ExportSummary softSummary = DatasetExporter.export(
            solver,
            new DatasetExporter.ExportOptions(softCsv, RetrogradeSolver.TargetMode.SOFT, false, true, 42L)
        );
        List<String> softLines = Files.readAllLines(softCsv);
        assertEquals(solver.nonTerminalStateCount(), softSummary.totalSamples());
        assertEquals(softSummary.totalSamples(), softLines.size());
        assertTrue(softSummary.placementSamples() > 0);
        assertTrue(softSummary.movementSamples() > 0);

        // Validate every single row: 50 columns, valid inputs, legal non-zero targets summing to 1.0.
        for (String line : softLines) {
            String[] cols = line.split(",", -1);
            assertEquals(MoveEncoder.INPUT_SIZE + MoveEncoder.OUTPUT_SIZE, cols.length);

            Player[] board = new Player[BoardTopology.BOARD_SIZE];
            for (int i = 0; i < 9; i++) {
                double val = Double.parseDouble(cols[i]);
                assertTrue(val == -1.0 || val == 0.0 || val == 1.0, "Unexpected input value: " + val);
                if (val == 1.0) {
                    board[i] = Player.X;
                } else if (val == -1.0) {
                    board[i] = Player.O;
                }
            }
            GameEngine engine = GameEngine.fromState(board, Player.X);
            boolean[] legalMask = MoveEncoder.legalActionMask(engine);

            double targetSum = 0.0;
            int nonZeroTargets = 0;
            for (int a = 0; a < 41; a++) {
                double p = Double.parseDouble(cols[9 + a]);
                assertTrue(p >= 0.0 && p <= 1.0);
                if (p > 0.0) {
                    assertTrue(legalMask[a], "Target assigned probability to illegal action index " + a);
                    nonZeroTargets++;
                }
                targetSum += p;
            }
            assertTrue(nonZeroTargets >= 1);
            assertEquals(1.0, targetSum, 1e-4);
        }

        // 2. One-hot target distribution with header
        Path oneHotCsv = tempDir.resolve("onehot.csv");
        DatasetExporter.export(
            solver,
            new DatasetExporter.ExportOptions(oneHotCsv, RetrogradeSolver.TargetMode.ONE_HOT, true, false, 42L)
        );
        List<String> oneHotLines = Files.readAllLines(oneHotCsv);
        assertEquals(solver.nonTerminalStateCount() + 1, oneHotLines.size());
        assertTrue(oneHotLines.get(0).startsWith("c0,c1,c2,c3,c4,c5,c6,c7,c8,a0,"));
        assertTrue(oneHotLines.get(0).endsWith(",a40"));

        // Validate every single one-hot data row has strictly one 1.0 and forty 0.0 targets.
        for (int r = 1; r < oneHotLines.size(); r++) {
            String[] cols = oneHotLines.get(r).split(",", -1);
            assertEquals(50, cols.length);
            int ones = 0;
            for (int a = 0; a < 41; a++) {
                double v = Double.parseDouble(cols[9 + a]);
                if (v == 1.0) {
                    ones++;
                } else {
                    assertEquals(0.0, v, 1e-9);
                }
            }
            assertEquals(1, ones, "Row " + r + " must have strictly one 1.0 target");
        }
    }

    @Test
    void deterministicSeedProducesIdenticalFilesAndDifferentSeedDiffers(@TempDir Path tempDir) throws IOException {
        RetrogradeSolver solver = RetrogradeSolver.solve();
        Path a = tempDir.resolve("a.csv");
        Path b = tempDir.resolve("b.csv");
        Path c = tempDir.resolve("c.csv");

        DatasetExporter.export(solver, new DatasetExporter.ExportOptions(a, RetrogradeSolver.TargetMode.SOFT, false, true, 99L));
        DatasetExporter.export(solver, new DatasetExporter.ExportOptions(b, RetrogradeSolver.TargetMode.SOFT, false, true, 99L));
        DatasetExporter.export(solver, new DatasetExporter.ExportOptions(c, RetrogradeSolver.TargetMode.SOFT, false, true, 100L));

        assertEquals(Files.readString(a), Files.readString(b));
        assertNotEquals(Files.readString(a), Files.readString(c));
    }

    @Test
    void cliParsesHelpGeneratesDatasetAndRejectsBadFlags(@TempDir Path tempDir) {
        assertEquals(0, DatasetGenMain.run(new String[] {"--help"}));
        assertEquals(0, DatasetGenMain.run(new String[] {"-h"}));

        Path out = tempDir.resolve("cli_out.csv");
        assertEquals(0, DatasetGenMain.run(new String[] {
            "--output", out.toString(),
            "--target-mode", "onehot",
            "--header",
            "--no-shuffle",
            "--seed", "7"
        }));
        assertTrue(Files.isRegularFile(out));

        // Writing to an existing directory path triggers IOException -> exit code 1.
        assertEquals(1, DatasetGenMain.run(new String[] {"--output", tempDir.toString()}));

        assertEquals(1, DatasetGenMain.run(new String[] {"--output", "   "}));
        assertEquals(1, DatasetGenMain.run(new String[] {"--seed", "not-a-number"}));
        assertEquals(1, DatasetGenMain.run(new String[] {"--target-mode", "bogus"}));
        assertEquals(1, DatasetGenMain.run(new String[] {"--unknown-flag"}));

        assertThrows(
            IllegalArgumentException.class,
            () -> DatasetGenMain.DatasetCliArgs.parse(new String[] {"--output"})
        );
    }

    @Test
    void neuralNetworkConfigTemplateConformsStrictlyToNeuralNetworkV060Schema() throws IOException {
        Path configPath = Path.of("configs", "tictactoe_nn_config.json");
        assertTrue(Files.isRegularFile(configPath), "Missing configs/tictactoe_nn_config.json");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(configPath.toFile());
        assertTrue(root.isObject());
        assertEquals(Set.of("model", "training", "server"), fieldNamesOf(root));

        // 1. Strict 'model' section (9 -> 128 relu -> 128 relu -> 41 softmax = 23,081 parameters)
        JsonNode model = root.get("model");
        assertEquals(Set.of("layers", "init_seed", "load_path", "save_path", "save_on_finish"), fieldNamesOf(model));
        JsonNode layers = model.get("layers");
        assertTrue(layers.isArray());
        assertEquals(4, layers.size());

        assertEquals(Set.of("size"), fieldNamesOf(layers.get(0)));
        assertEquals(MoveEncoder.INPUT_SIZE, layers.get(0).get("size").asInt());

        int[] expectedSizes = {MoveEncoder.INPUT_SIZE, 128, 128, MoveEncoder.OUTPUT_SIZE};
        String[] expectedActivations = {null, "relu", "relu", "softmax"};
        long totalParams = 0L;
        for (int i = 1; i < expectedSizes.length; i++) {
            JsonNode layer = layers.get(i);
            assertEquals(Set.of("size", "activation"), fieldNamesOf(layer));
            assertEquals(expectedSizes[i], layer.get("size").asInt());
            assertEquals(expectedActivations[i], layer.get("activation").asText());
            totalParams += (long) expectedSizes[i - 1] * expectedSizes[i] + expectedSizes[i];
        }
        assertEquals(23_081L, totalParams);
        assertTrue(model.get("init_seed").isIntegralNumber());
        assertEquals("", model.get("load_path").asText());
        assertEquals("models/tictactoe_model.json", model.get("save_path").asText());
        assertTrue(model.get("save_on_finish").asBoolean());

        // 2. Strict 'training' section
        JsonNode training = root.get("training");
        Set<String> allowedTrainingKeys = Set.of(
            "dataset_path",
            "dataset_has_header",
            "dataset_generator",
            "generator_samples",
            "generator_seed",
            "loss",
            "optimizer",
            "epochs",
            "batch_size",
            "learning_rate",
            "adam_beta1",
            "adam_beta2",
            "adam_epsilon",
            "shuffle",
            "num_threads",
            "seed",
            "validation_split",
            "validation_shuffle",
            "keep_best_validation_model",
            "log_every_epochs"
        );
        Set<String> actualTrainingKeys = fieldNamesOf(training);
        assertTrue(
            allowedTrainingKeys.containsAll(actualTrainingKeys),
            "Unexpected keys in training section: " + actualTrainingKeys
        );
        assertEquals("data/tictactoe_dataset.csv", training.get("dataset_path").asText());
        assertFalse(training.get("dataset_has_header").asBoolean());
        assertEquals("cce", training.get("loss").asText());
        assertEquals("adam", training.get("optimizer").asText());
        assertEquals(0.001, training.get("learning_rate").asDouble(), 1e-12);
        assertTrue(training.get("epochs").asInt() > 0);
        assertTrue(training.get("batch_size").asInt() > 0);
        assertTrue(training.get("num_threads").asInt() >= 1);
        assertTrue(training.get("keep_best_validation_model").asBoolean());

        // 3. Strict 'server' section
        JsonNode server = root.get("server");
        assertEquals(Set.of("host", "port", "num_workers"), fieldNamesOf(server));
        assertEquals("127.0.0.1", server.get("host").asText());
        assertEquals(8080, server.get("port").asInt());
        assertTrue(server.get("num_workers").asInt() >= 1);
    }

    private static Set<String> fieldNamesOf(JsonNode objectNode) {
        Set<String> names = new HashSet<>();
        Iterator<String> it = objectNode.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }
}
