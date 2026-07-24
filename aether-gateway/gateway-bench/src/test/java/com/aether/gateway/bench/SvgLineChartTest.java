package com.aether.gateway.bench;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SvgLineChartTest {

    @Test
    void rendersOnePolylinePerSeries() {
        Map<String, List<Double>> series = new LinkedHashMap<>();
        series.put("hit rate", List.of(0.9, 0.5, 0.1));
        series.put("false-hit rate", List.of(0.8, 0.4, 0.05));

        String svg = SvgLineChart.render("Threshold sweep", List.of(0.80, 0.90, 0.99), series);

        assertThat(svg).startsWith("<svg");
        assertThat(svg).endsWith("</svg>");
        assertThat(countOccurrences(svg, "<polyline")).isEqualTo(2);
    }

    @Test
    void includesEverySeriesLabelInTheLegend() {
        Map<String, List<Double>> series = new LinkedHashMap<>();
        series.put("hit rate", List.of(1.0, 0.0));

        String svg = SvgLineChart.render("Title", List.of(0.80, 0.99), series);

        assertThat(svg).contains("hit rate");
    }

    @Test
    void plotsHigherValuesNearerTheTopOfTheChart() {
        Map<String, List<Double>> series = new LinkedHashMap<>();
        series.put("rate", List.of(1.0, 0.0));

        String svg = SvgLineChart.render("Title", List.of(0.80, 0.99), series);

        String pointsAttribute = svg.substring(svg.indexOf("points=\"") + 8);
        pointsAttribute = pointsAttribute.substring(0, pointsAttribute.indexOf('"'));
        String[] points = pointsAttribute.trim().split(" ");
        double firstY = Double.parseDouble(points[0].split(",")[1]);
        double lastY = Double.parseDouble(points[points.length - 1].split(",")[1]);

        assertThat(firstY).as("value 1.0 should be plotted nearer the top (smaller y-pixel) than value 0.0").isLessThan(lastY);
    }

    @Test
    void rejectsASeriesWhoseLengthDoesNotMatchTheXAxis() {
        Map<String, List<Double>> series = new LinkedHashMap<>();
        series.put("hit rate", List.of(1.0, 0.0, 0.5));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> SvgLineChart.render("Title", List.of(0.80, 0.99), series))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
