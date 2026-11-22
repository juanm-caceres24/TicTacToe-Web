package com.tictactoe.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tictactoe.DatasetGenMain;
import com.tictactoe.engine.BoardTopology;
import com.tictactoe.engine.GameEngine;
import com.tictactoe.engine.MoveEncoder;
import com.tictactoe.engine.Player;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

        String firstDataRow = oneHotLines.get(1);
        String[] cols = firstDataRow.split(",", -1);
        int ones = 0;
        for (int a = 0; a < 41; a++) {
            double v = Double.parseDouble(cols[9 + a]);
            if (v == 1.0) {
                ones++;
            } else {
                assertEquals(0.0, v, 1e-9);
            }
        }
        assertEquals(1, ones);
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
            "--target-mode", "soft",
            "--seed", "7"
        }));
        assertTrue(Files.isRegularFile(out));

        assertEquals(1, DatasetGenMain.run(new String[] {"--seed", "not-a-number"}));
        assertEquals(1, DatasetGenMain.run(new String[] {"--target-mode", "bogus"}));
        assertEquals(1, DatasetGenMain.run(new String[] {"--unknown-flag"}));

        assertThrows(
            IllegalArgumentException.class,
            () -> DatasetGenMain.DatasetCliArgs.parse(new String[] {"--output"})
        );
    }
}
