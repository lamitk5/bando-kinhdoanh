package vn.bando.spatial.map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import vn.bando.spatial.BufferRequest;
import vn.bando.spatial.OsmRefs;

@Service
public class MapService {

    static final int POINT_LIMIT = 4000;
    static final double MAX_SPAN = 0.25;

    private final MapRepository repository;
    private final ObjectMapper mapper;

    public MapService(MapRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public ObjectNode districts() {
        ObjectNode collection = mapper.createObjectNode();
        collection.put("type", "FeatureCollection");
        ArrayNode features = collection.putArray("features");
        for (MapRepository.DistrictShape district : repository.districts()) {
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            try {
                feature.set("geometry", mapper.readTree(district.geoJson()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Ranh quận không đọc được", exception);
            }
            feature.putObject("properties").put("name", district.name());
        }
        return collection;
    }

    public ObjectNode points(double west, double south, double east, double north, String businessType) {
        String business = business(businessType);
        bounds(west, south, east, north);
        var rows = repository.points(west, south, east, north, business, POINT_LIMIT + 1);
        boolean truncated = rows.size() > POINT_LIMIT;
        if (truncated) {
            rows = rows.subList(0, POINT_LIMIT);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("business_type", business);
        body.put("truncated", truncated);
        body.put("role_note", "attractor là điểm hút khách. competitor là cơ sở cùng ngành đang chọn.");
        ArrayNode features = body.putArray("features");
        for (MapRepository.MapPoint point : rows) {
            ObjectNode feature = features.addObject();
            feature.put("district", point.district());
            if (point.name() == null) {
                feature.putNull("name");
            } else {
                feature.put("name", point.name());
            }
            feature.put("role", point.role());
            feature.put("category", point.category());
            if (point.businessType() == null) {
                feature.putNull("business_type");
            } else {
                feature.put("business_type", point.businessType());
            }
            feature.put("lat", point.lat());
            feature.put("lon", point.lon());
            feature.put("osm_url", OsmRefs.url(point.osmType(), point.osmId()));
        }
        return body;
    }

    public ObjectNode heatmap(double west, double south, double east, double north, String businessType) {
        String business = business(businessType);
        bounds(west, south, east, north);
        ObjectNode body = mapper.createObjectNode();
        body.put("business_type", business);
        body.put("cell_degrees", 0.002);
        body.put("cell_note", "Mỗi ô là góc tây nam của lưới 0,002 độ. Giá trị là số điểm, không phải mật độ trên km².");
        ArrayNode cells = body.putArray("cells");
        for (MapRepository.HeatCell cell : repository.heatmap(west, south, east, north, business)) {
            cells.addObject()
                    .put("lat", cell.lat())
                    .put("lon", cell.lon())
                    .put("attractors", cell.attractors())
                    .put("competitors", cell.competitors());
        }
        return body;
    }

    private static String business(String businessType) {
        try {
            return BufferRequest.normalize(21.03, 105.80, businessType).businessType();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static void bounds(double west, double south, double east, double north) {
        if (!(south < north) || !(west < east)
                || south < -90 || north > 90 || west < -180 || east > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khung bản đồ không hợp lệ");
        }
        if (north - south > MAX_SPAN || east - west > MAX_SPAN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khung bản đồ rộng hơn 0,25 độ");
        }
    }
}
