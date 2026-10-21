package com.jdesk.frame.icon;

import com.jdesk.frame.laf.JdfColors;
import com.jdesk.util.xml.SecureXmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight SVG renderer for framework icons, covering the subset that common IntelliJ Platform icons use: paths,
 * basic shapes, groups, fill and stroke colors, simple gradients, opacity and common transforms. Filters, clipping,
 * text and unusual transforms are ignored.
 */
final class SvgImageLoader {

    private static final Pattern TRANSFORM_PATTERN = Pattern.compile("([a-zA-Z]+)\\(([^)]*)\\)");

    private SvgImageLoader() {
    }

    static BufferedImage render(InputStream input, int targetWidth, int targetHeight) {
        Document document = SecureXmlParser.parse(input, "svg icon");
        Element root = document.getDocumentElement();
        ViewBox viewBox = readViewBox(root);
        int width = targetWidth > 0 ? targetWidth : Math.max(1, Math.round((float) viewBox.width));
        int height = targetHeight > 0 ? targetHeight : Math.max(1, Math.round((float) viewBox.height));

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double sx = width / viewBox.width;
            double sy = height / viewBox.height;
            g.scale(sx, sy);
            g.translate(-viewBox.x, -viewBox.y);

            Definitions definitions = Definitions.from(root);
            SvgState rootState = SvgState.root();
            renderChildren(root, g, rootState.with(root, definitions), definitions);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void renderChildren(Element parent, Graphics2D g, SvgState state, Definitions definitions) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                renderElement(element, g, state, definitions);
            }
        }
    }

    private static void renderElement(Element element, Graphics2D g, SvgState inherited, Definitions definitions) {
        String tag = element.getTagName();
        if ("defs".equals(tag) || "title".equals(tag) || "desc".equals(tag)
                || "clipPath".equals(tag) || "filter".equals(tag)) {
            return;
        }

        SvgState state = inherited.with(element, definitions);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.transform(parseTransform(element.getAttribute("transform")));
            if ("g".equals(tag) || "svg".equals(tag)) {
                renderChildren(element, g2, state, definitions);
                return;
            }

            Shape shape = createShape(element, state);
            if (shape == null) {
                renderChildren(element, g2, state, definitions);
                return;
            }

            if (state.fill != null && state.fillAppliesTo(tag)) {
                g2.setPaint(withAlpha(state.fill, state.alpha * state.fillAlpha));
                g2.fill(shape);
            }
            if (state.stroke != null && state.strokeWidth > 0f) {
                Stroke stroke = new BasicStroke(state.strokeWidth, state.lineCap, state.lineJoin);
                g2.setStroke(stroke);
                g2.setPaint(withAlpha(state.stroke, state.alpha * state.strokeAlpha));
                g2.draw(shape);
            }
        } finally {
            g2.dispose();
        }
    }

    private static Shape createShape(Element element, SvgState state) {
        String tag = element.getTagName();
        return switch (tag) {
            case "path" -> SvgPathParser.parse(element.getAttribute("d"), state.windingRule);
            case "rect" -> createRect(element);
            case "circle" -> createCircle(element);
            case "ellipse" -> createEllipse(element);
            case "line" -> new Line2D.Double(
                    number(element.getAttribute("x1"), 0),
                    number(element.getAttribute("y1"), 0),
                    number(element.getAttribute("x2"), 0),
                    number(element.getAttribute("y2"), 0));
            case "polyline" -> createPointsPath(element, false, state.windingRule);
            case "polygon" -> createPointsPath(element, true, state.windingRule);
            default -> null;
        };
    }

    private static Shape createRect(Element element) {
        double x = number(element.getAttribute("x"), 0);
        double y = number(element.getAttribute("y"), 0);
        double width = number(element.getAttribute("width"), 0);
        double height = number(element.getAttribute("height"), 0);
        double rx = number(element.getAttribute("rx"), 0);
        double ry = number(element.getAttribute("ry"), rx);
        if (rx > 0 || ry > 0) {
            return new RoundRectangle2D.Double(x, y, width, height, rx * 2, ry * 2);
        }
        return new Rectangle2D.Double(x, y, width, height);
    }

    private static Shape createCircle(Element element) {
        double cx = number(element.getAttribute("cx"), 0);
        double cy = number(element.getAttribute("cy"), 0);
        double r = number(element.getAttribute("r"), 0);
        return new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2);
    }

    private static Shape createEllipse(Element element) {
        double cx = number(element.getAttribute("cx"), 0);
        double cy = number(element.getAttribute("cy"), 0);
        double rx = number(element.getAttribute("rx"), 0);
        double ry = number(element.getAttribute("ry"), 0);
        return new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2);
    }

    private static Shape createPointsPath(Element element, boolean closed, int windingRule) {
        String[] tokens = element.getAttribute("points").trim().split("[\\s,]+");
        Path2D path = new Path2D.Double(windingRule);
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            double x = number(tokens[i], 0);
            double y = number(tokens[i + 1], 0);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        if (closed) {
            path.closePath();
        }
        return path;
    }

    private static Paint withAlpha(Color color, float alpha) {
        float safeAlpha = Math.max(0f, Math.min(1f, alpha));
        int a = Math.round(color.getAlpha() * safeAlpha);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }

    private static ViewBox readViewBox(Element root) {
        String viewBox = root.getAttribute("viewBox");
        if (!viewBox.isBlank()) {
            String[] parts = viewBox.trim().split("[\\s,]+");
            if (parts.length == 4) {
                return new ViewBox(
                        number(parts[0], 0),
                        number(parts[1], 0),
                        Math.max(1, number(parts[2], 16)),
                        Math.max(1, number(parts[3], 16)));
            }
        }
        double width = number(root.getAttribute("width"), 16);
        double height = number(root.getAttribute("height"), width);
        return new ViewBox(0, 0, Math.max(1, width), Math.max(1, height));
    }

    private static AffineTransform parseTransform(String value) {
        AffineTransform transform = new AffineTransform();
        if (value == null || value.isBlank()) {
            return transform;
        }

        Matcher matcher = TRANSFORM_PATTERN.matcher(value);
        while (matcher.find()) {
            String name = matcher.group(1);
            double[] values = numbers(matcher.group(2));
            switch (name) {
                case "translate" -> transform.translate(
                        values.length > 0 ? values[0] : 0,
                        values.length > 1 ? values[1] : 0);
                case "scale" -> transform.scale(
                        values.length > 0 ? values[0] : 1,
                        values.length > 1 ? values[1] : (values.length > 0 ? values[0] : 1));
                case "rotate" -> {
                    double radians = Math.toRadians(values.length > 0 ? values[0] : 0);
                    if (values.length > 2) {
                        transform.rotate(radians, values[1], values[2]);
                    } else {
                        transform.rotate(radians);
                    }
                }
                case "matrix" -> {
                    if (values.length >= 6) {
                        transform.concatenate(new AffineTransform(
                                values[0], values[1], values[2], values[3], values[4], values[5]));
                    }
                }
                default -> {
                    // skewX and skewY are ignored.
                }
            }
        }
        return transform;
    }

    static double number(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = value.trim();
        if (cleaned.endsWith("px")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double[] numbers(String value) {
        if (value == null || value.isBlank()) {
            return new double[0];
        }
        String[] tokens = value.trim().split("[\\s,]+");
        double[] result = new double[tokens.length];
        int count = 0;
        for (String token : tokens) {
            if (!token.isBlank()) {
                result[count++] = number(token, 0);
            }
        }
        return java.util.Arrays.copyOf(result, count);
    }

    /** The SVG viewBox, used to scale source coordinates to the target pixel size. */
    private record ViewBox(double x, double y, double width, double height) {
    }

    /** Colors defined in the SVG defs; a gradient resolves to the color of its first stop. */
    private static final class Definitions {
        private final Map<String, Color> colors = new HashMap<>();

        static Definitions from(Element root) {
            Definitions definitions = new Definitions();
            NodeList nodes = root.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element element && element.hasAttribute("id")
                        && (element.getTagName().endsWith("Gradient"))) {
                    Color color = firstStopColor(element, definitions);
                    if (color != null) {
                        definitions.colors.put(element.getAttribute("id"), color);
                    }
                }
            }
            return definitions;
        }

        Color resolve(String paint) {
            if (paint == null) {
                return null;
            }
            String value = paint.trim();
            if (value.startsWith("url(#") && value.endsWith(")")) {
                return colors.get(value.substring(5, value.length() - 1));
            }
            return null;
        }

        private static Color firstStopColor(Element gradient, Definitions definitions) {
            NodeList children = gradient.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element stop && "stop".equals(stop.getTagName())) {
                    Map<String, String> style = parseStyle(stop.getAttribute("style"));
                    String stopColor = firstPresent(stop.getAttribute("stop-color"), style.get("stop-color"));
                    Color color = parseColor(stopColor, definitions, Color.BLACK);
                    float opacity = (float) number(firstPresent(stop.getAttribute("stop-opacity"),
                            style.get("stop-opacity")), 1);
                    if (opacity < 1f) {
                        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                                Math.round(color.getAlpha() * opacity));
                    }
                    return color;
                }
            }
            return null;
        }
    }

    /** Inherited style state, passed down the SVG tree while rendering. */
    private static final class SvgState {
        private final Color fill;
        private final Color stroke;
        private final float strokeWidth;
        private final float alpha;
        private final float fillAlpha;
        private final float strokeAlpha;
        private final int lineCap;
        private final int lineJoin;
        private final int windingRule;

        private SvgState(Color fill, Color stroke, float strokeWidth, float alpha,
                         float fillAlpha, float strokeAlpha, int lineCap, int lineJoin, int windingRule) {
            this.fill = fill;
            this.stroke = stroke;
            this.strokeWidth = strokeWidth;
            this.alpha = alpha;
            this.fillAlpha = fillAlpha;
            this.strokeAlpha = strokeAlpha;
            this.lineCap = lineCap;
            this.lineJoin = lineJoin;
            this.windingRule = windingRule;
        }

        static SvgState root() {
            return new SvgState(Color.BLACK, null, 1f, 1f, 1f, 1f,
                    BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, Path2D.WIND_NON_ZERO);
        }

        SvgState with(Element element, Definitions definitions) {
            Map<String, String> style = parseStyle(element.getAttribute("style"));
            Color newFill = colorValue(firstPresent(element.getAttribute("fill"), style.get("fill")),
                    definitions, fill);
            Color newStroke = colorValue(firstPresent(element.getAttribute("stroke"), style.get("stroke")),
                    definitions, stroke);
            float newStrokeWidth = (float) number(firstPresent(element.getAttribute("stroke-width"),
                    style.get("stroke-width")), strokeWidth);
            float newAlpha = alpha * (float) number(firstPresent(element.getAttribute("opacity"),
                    style.get("opacity")), 1);
            float newFillAlpha = (float) number(firstPresent(element.getAttribute("fill-opacity"),
                    style.get("fill-opacity")), fillAlpha);
            float newStrokeAlpha = (float) number(firstPresent(element.getAttribute("stroke-opacity"),
                    style.get("stroke-opacity")), strokeAlpha);
            int newLineCap = lineCap(firstPresent(element.getAttribute("stroke-linecap"),
                    style.get("stroke-linecap")), lineCap);
            int newLineJoin = lineJoin(firstPresent(element.getAttribute("stroke-linejoin"),
                    style.get("stroke-linejoin")), lineJoin);
            int newWindingRule = fillRule(firstPresent(element.getAttribute("fill-rule"),
                    style.get("fill-rule")), windingRule);
            return new SvgState(newFill, newStroke, newStrokeWidth, newAlpha,
                    newFillAlpha, newStrokeAlpha, newLineCap, newLineJoin, newWindingRule);
        }

        boolean fillAppliesTo(String tag) {
            return !"line".equals(tag) && !"polyline".equals(tag);
        }
    }

    private static Map<String, String> parseStyle(String style) {
        Map<String, String> result = new HashMap<>();
        if (style == null || style.isBlank()) {
            return result;
        }
        String[] declarations = style.split(";");
        for (String declaration : declarations) {
            int colon = declaration.indexOf(':');
            if (colon > 0) {
                result.put(declaration.substring(0, colon).trim(),
                        declaration.substring(colon + 1).trim());
            }
        }
        return result;
    }

    private static Color colorValue(String value, Definitions definitions, Color inherited) {
        if (value == null || value.isBlank()) {
            return inherited;
        }
        if ("none".equals(value.trim())) {
            return null;
        }
        return parseColor(value, definitions, inherited);
    }

    private static Color parseColor(String value, Definitions definitions, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String color = value.trim();
        Color definitionColor = definitions.resolve(color);
        if (definitionColor != null) {
            return definitionColor;
        }
        if ("currentColor".equals(color)) {
            return JdfColors.text();
        }
        if (color.startsWith("#")) {
            return parseHexColor(color, fallback);
        }
        if (color.startsWith("rgb")) {
            return parseRgbColor(color, fallback);
        }
        return switch (color.toLowerCase(Locale.ROOT)) {
            case "black" -> Color.BLACK;
            case "white" -> Color.WHITE;
            case "red" -> Color.RED;
            case "green" -> Color.GREEN;
            case "blue" -> Color.BLUE;
            case "gray", "grey" -> Color.GRAY;
            case "transparent" -> new Color(0, 0, 0, 0);
            default -> fallback;
        };
    }

    private static Color parseHexColor(String color, Color fallback) {
        String hex = color.substring(1);
        try {
            if (hex.length() == 3) {
                int r = Integer.parseInt(hex.substring(0, 1) + hex.charAt(0), 16);
                int g = Integer.parseInt(hex.substring(1, 2) + hex.charAt(1), 16);
                int b = Integer.parseInt(hex.substring(2, 3) + hex.charAt(2), 16);
                return new Color(r, g, b);
            }
            if (hex.length() == 4) {
                int r = Integer.parseInt(hex.substring(0, 1) + hex.charAt(0), 16);
                int g = Integer.parseInt(hex.substring(1, 2) + hex.charAt(1), 16);
                int b = Integer.parseInt(hex.substring(2, 3) + hex.charAt(2), 16);
                int a = Integer.parseInt(hex.substring(3, 4) + hex.charAt(3), 16);
                return new Color(r, g, b, a);
            }
            if (hex.length() == 6) {
                return new Color(Integer.parseInt(hex, 16));
            }
            if (hex.length() == 8) {
                int rgba = (int) Long.parseLong(hex, 16);
                return new Color((rgba >> 24) & 0xFF, (rgba >> 16) & 0xFF,
                        (rgba >> 8) & 0xFF, rgba & 0xFF);
            }
        } catch (NumberFormatException ignored) {
            return fallback;
        }
        return fallback;
    }

    private static Color parseRgbColor(String color, Color fallback) {
        int start = color.indexOf('(');
        int end = color.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return fallback;
        }
        String[] parts = color.substring(start + 1, end).split("[\\s,]+");
        if (parts.length < 3) {
            return fallback;
        }
        int r = clampColor(number(parts[0], 0));
        int g = clampColor(number(parts[1], 0));
        int b = clampColor(number(parts[2], 0));
        int a = parts.length > 3 ? Math.round((float) number(parts[3], 1) * 255) : 255;
        return new Color(r, g, b, Math.max(0, Math.min(255, a)));
    }

    private static int clampColor(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static int lineCap(String value, int fallback) {
        return switch (value) {
            case "round" -> BasicStroke.CAP_ROUND;
            case "square" -> BasicStroke.CAP_SQUARE;
            case "butt" -> BasicStroke.CAP_BUTT;
            default -> fallback;
        };
    }

    private static int lineJoin(String value, int fallback) {
        return switch (value) {
            case "round" -> BasicStroke.JOIN_ROUND;
            case "bevel" -> BasicStroke.JOIN_BEVEL;
            case "miter" -> BasicStroke.JOIN_MITER;
            default -> fallback;
        };
    }

    private static int fillRule(String value, int fallback) {
        return "evenodd".equals(value) ? Path2D.WIND_EVEN_ODD : fallback;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
