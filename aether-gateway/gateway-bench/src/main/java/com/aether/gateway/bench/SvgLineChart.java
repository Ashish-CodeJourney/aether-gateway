package com.aether.gateway.bench;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal, dependency-free SVG line chart, so `make bench` can produce
 * plots (PRD section 16.3) without a Python/matplotlib toolchain in the
 * reproducibility path. Assumes every y value is in [0, 1] (every M6
 * experiment plots a rate).
 */
public final class SvgLineChart {

    private static final int WIDTH = 640;
    private static final int HEIGHT = 400;
    private static final int MARGIN = 50;
    private static final String[] COLORS = {"#1f77b4", "#d62728", "#2ca02c", "#9467bd"};

    private SvgLineChart() {
    }

    public static String render(String title, List<Double> xValues, Map<String, List<Double>> seriesByLabel) {
        for (List<Double> values : seriesByLabel.values()) {
            if (values.size() != xValues.size()) {
                throw new IllegalArgumentException(
                        "Series length (%d) must match the x-axis length (%d)".formatted(values.size(), xValues.size()));
            }
        }

        double xMin = xValues.get(0);
        double xMax = xValues.get(xValues.size() - 1);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(WIDTH)
                .append("\" height=\"").append(HEIGHT).append("\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT).append("\">");
        svg.append("<text x=\"").append(WIDTH / 2).append("\" y=\"20\" text-anchor=\"middle\" font-size=\"14\">")
                .append(escape(title)).append("</text>");
        svg.append("<rect x=\"").append(MARGIN).append("\" y=\"").append(MARGIN)
                .append("\" width=\"").append(WIDTH - 2 * MARGIN).append("\" height=\"").append(HEIGHT - 2 * MARGIN)
                .append("\" fill=\"none\" stroke=\"#ccc\"/>");

        int colorIndex = 0;
        int legendY = MARGIN;
        for (Map.Entry<String, List<Double>> series : seriesByLabel.entrySet()) {
            String color = COLORS[colorIndex % COLORS.length];
            svg.append("<polyline fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"2\" points=\"");
            List<Double> values = series.getValue();
            for (int i = 0; i < values.size(); i++) {
                double px = MARGIN + (xValues.get(i) - xMin) / (xMax - xMin) * (WIDTH - 2 * MARGIN);
                double py = MARGIN + (1 - values.get(i)) * (HEIGHT - 2 * MARGIN);
                svg.append(String.format(Locale.ROOT, "%.2f,%.2f ", px, py));
            }
            svg.append("\"/>");
            svg.append("<text x=\"").append(WIDTH - MARGIN + 5).append("\" y=\"").append(legendY)
                    .append("\" font-size=\"11\" fill=\"").append(color).append("\">")
                    .append(escape(series.getKey())).append("</text>");
            legendY += 15;
            colorIndex++;
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
