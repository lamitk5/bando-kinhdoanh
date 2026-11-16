package vn.bando.spatial;

public final class OsmRefs {

    private OsmRefs() {
    }

    public static String url(String osmType, long osmId) {
        String kind = switch (osmType) {
            case "N" -> "node";
            case "W" -> "way";
            case "R" -> "relation";
            default -> throw new IllegalArgumentException("osm_type không hợp lệ: " + osmType);
        };
        return "https://www.openstreetmap.org/" + kind + "/" + osmId;
    }
}
