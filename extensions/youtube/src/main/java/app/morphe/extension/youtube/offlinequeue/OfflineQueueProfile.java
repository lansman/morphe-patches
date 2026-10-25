/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.offlinequeue;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest.PlaylistItem;

/** Small, explainable local model used to rank offline queue candidates. */
public final class OfflineQueueProfile {

    public enum Signal {
        MANUAL_ADD(3.0),
        KEPT(0.5),
        WATCHED(2.0),
        REMOVED_DURING_REVIEW(-1.5),
        SHOW_LESS(-3.0);

        final double weight;

        Signal(double weight) {
            this.weight = weight;
        }
    }

    private static final int COLD_START_SIGNALS = 5;
    private static final int EXPLORATION_INTERVAL = 5;
    private static final double MAX_ABSOLUTE_WEIGHT = 12.0;
    private static final double DECAY = 0.995;

    private final Map<String, Double> termWeights = new HashMap<>();
    private final Map<String, Double> channelWeights = new HashMap<>();
    private int signalCount;

    public static OfflineQueueProfile fromJson(String json) {
        OfflineQueueProfile profile = new OfflineQueueProfile();
        if (json == null || json.isBlank()) return profile;
        try {
            JSONObject root = new JSONObject(json);
            profile.signalCount = Math.max(0, root.optInt("signalCount"));
            readWeights(root.optJSONObject("terms"), profile.termWeights);
            readWeights(root.optJSONObject("channels"), profile.channelWeights);
        } catch (Exception ignored) {
            // A malformed local profile behaves like a clean neutral profile.
        }
        return profile;
    }

    public String toJson() {
        return new JSONObject()
                .put("schemaVersion", 1)
                .put("signalCount", signalCount)
                .put("terms", new JSONObject(termWeights))
                .put("channels", new JSONObject(channelWeights))
                .toString();
    }

    public int signalCount() {
        return signalCount;
    }

    public void apply(PlaylistItem item, Signal signal) {
        decay(termWeights);
        decay(channelWeights);
        List<String> terms = terms(item.title());
        double perTerm = terms.isEmpty() ? 0 : signal.weight / Math.sqrt(terms.size());
        for (String term : terms) add(termWeights, term, perTerm);
        if (!item.channel().isBlank()) add(channelWeights, normalize(item.channel()), signal.weight);
        signalCount++;
    }

    /**
     * Ranks candidates without a bundled topic taxonomy. Before enough feedback exists, server
     * order is retained unless the user supplied free-form seed interests.
     */
    public List<PlaylistItem> rank(List<PlaylistItem> source, String seedInterests) {
        List<String> seeds = terms(seedInterests == null ? "" : seedInterests);
        if (signalCount < COLD_START_SIGNALS && seeds.isEmpty()) return new ArrayList<>(source);

        Map<String, Integer> originalIndex = new HashMap<>();
        for (int i = 0; i < source.size(); i++) originalIndex.put(source.get(i).videoId(), i);

        List<PlaylistItem> ranked = new ArrayList<>(source);
        ranked.sort(Comparator
                .comparingDouble((PlaylistItem item) -> score(item, seeds)).reversed()
                .thenComparingInt(item -> originalIndex.get(item.videoId())));

        // Reserve one deterministic slot in five for an item from the unmodified feed order.
        // This keeps new topics visible and prevents a self-reinforcing filter bubble.
        if (signalCount >= COLD_START_SIGNALS) {
            Set<String> fixed = new HashSet<>();
            for (int slot = EXPLORATION_INTERVAL - 1; slot < ranked.size(); slot += EXPLORATION_INTERVAL) {
                for (PlaylistItem candidate : source) {
                    if (fixed.contains(candidate.videoId())) continue;
                    int current = ranked.indexOf(candidate);
                    if (current < slot) continue;
                    ranked.remove(current);
                    ranked.add(slot, candidate);
                    fixed.add(candidate.videoId());
                    break;
                }
            }
        }
        return ranked;
    }

    double score(PlaylistItem item, List<String> seeds) {
        List<String> itemTerms = terms(item.title());
        double score = channelWeights.getOrDefault(normalize(item.channel()), 0.0);
        for (String term : itemTerms) score += termWeights.getOrDefault(term, 0.0);
        for (String seed : seeds) {
            if (itemTerms.contains(seed)) score += 2.0;
        }
        return score;
    }

    static List<String> terms(String text) {
        List<String> result = new ArrayList<>();
        for (String value : normalize(text).split("[^\\p{L}\\p{N}]+")) {
            if (value.length() >= 3 && !result.contains(value)) result.add(value);
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static void add(Map<String, Double> weights, String key, double delta) {
        weights.put(key, Math.max(-MAX_ABSOLUTE_WEIGHT,
                Math.min(MAX_ABSOLUTE_WEIGHT, weights.getOrDefault(key, 0.0) + delta)));
    }

    private static void decay(Map<String, Double> weights) {
        weights.replaceAll((key, value) -> value * DECAY);
        weights.entrySet().removeIf(entry -> Math.abs(entry.getValue()) < 0.01);
    }

    private static void readWeights(JSONObject json, Map<String, Double> destination) {
        if (json == null) return;
        for (String key : json.keySet()) {
            double value = json.optDouble(key, 0);
            if (Double.isFinite(value) && value != 0) {
                destination.put(key, Math.max(-MAX_ABSOLUTE_WEIGHT, Math.min(MAX_ABSOLUTE_WEIGHT, value)));
            }
        }
    }
}
