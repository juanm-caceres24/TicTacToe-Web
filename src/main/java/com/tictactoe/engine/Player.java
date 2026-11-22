package com.tictactoe.engine;

import java.util.Locale;

/**
 * Represents one of the two players in Mobile Tic-Tac-Toe.
 */
public enum Player {
    X('X'),
    O('O');

    private final char symbol;

    Player(char symbol) {
        this.symbol = symbol;
    }

    /**
     * Returns the single-character display symbol ('X' or 'O').
     */
    public char symbol() {
        return symbol;
    }

    /**
     * Returns the opposing player.
     */
    public Player opponent() {
        return this == X ? O : X;
    }

    /**
     * Parses a player from a character symbol (case-insensitive).
     */
    public static Player fromSymbol(char symbol) {
        return switch (Character.toUpperCase(symbol)) {
            case 'X' -> X;
            case 'O' -> O;
            default -> throw new IllegalArgumentException("Invalid player symbol: '" + symbol + "'");
        };
    }

    /**
     * Parses a player from a string symbol (case-insensitive, trimmed).
     */
    public static Player fromSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("Player symbol must not be null");
        }
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 1) {
            throw new IllegalArgumentException("Invalid player symbol: '" + symbol + "'");
        }
        return fromSymbol(normalized.charAt(0));
    }
}
