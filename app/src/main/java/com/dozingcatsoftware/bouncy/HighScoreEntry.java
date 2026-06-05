package com.dozingcatsoftware.bouncy;

public class HighScoreEntry {
    public final String initials;
    public final long score;

    public HighScoreEntry(String initials, long score) {
        // Normalise : toujours 3 lettres majuscules, "AAA" par défaut
        if (initials == null || initials.trim().isEmpty()) {
            this.initials = "AAA";
        } else {
            String upper = initials.toUpperCase().trim();
            // Complète avec des A si moins de 3 caractères
            while (upper.length() < 3) upper = upper + "A";
            this.initials = upper.substring(0, 3);
        }
        this.score = score;
    }

    /** Sérialise en "AAA:123456" pour stockage dans SharedPreferences */
    public String serialize() {
        return this.initials + ":" + this.score;
    }

    /** Désérialise depuis "AAA:123456" */
    public static HighScoreEntry deserialize(String s) {
        try {
            String[] parts = s.split(":");
            if (parts.length == 2) {
                return new HighScoreEntry(parts[0], Long.parseLong(parts[1]));
            }
            // Ancien format (score seul, sans initiales)
            return new HighScoreEntry("AAA", Long.parseLong(s));
        } catch (NumberFormatException e) {
            return new HighScoreEntry("AAA", 0L);
        }
    }

    public boolean isValid() {
        return this.score > 0;
    }
}
