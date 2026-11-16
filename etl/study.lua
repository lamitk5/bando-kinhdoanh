-- Giữ lại đối tượng thô trong vùng trích xuất.
-- Phân loại nghiệp vụ nằm ở etl/sql, không nằm ở đây.
-- Hình học lưu EPSG:4326. geom của vùng là tâm tạm; SQL ghi đè bằng ST_PointOnSurface.

local poi = osm2pgsql.define_table({
    name = 'raw_poi',
    ids = { type = 'any', id_column = 'osm_id', type_column = 'osm_type' },
    columns = {
        { column = 'name', type = 'text' },
        { column = 'amenity', type = 'text' },
        { column = 'shop', type = 'text' },
        { column = 'office', type = 'text' },
        { column = 'building', type = 'text' },
        { column = 'railway', type = 'text' },
        { column = 'public_transport', type = 'text' },
        { column = 'highway', type = 'text' },
        { column = 'tags', type = 'jsonb' },
        { column = 'geom', type = 'point', projection = 4326, not_null = true },
        { column = 'area_geom', type = 'multipolygon', projection = 4326 },
    },
    indexes = {
        { column = 'geom', method = 'gist' },
    }
})

local road = osm2pgsql.define_table({
    name = 'raw_road',
    ids = { type = 'way', id_column = 'osm_id' },
    columns = {
        { column = 'name', type = 'text' },
        { column = 'highway', type = 'text', not_null = true },
        { column = 'tags', type = 'jsonb' },
        { column = 'geom', type = 'linestring', projection = 4326, not_null = true },
    },
    indexes = {
        { column = 'geom', method = 'gist' },
        { column = 'highway', method = 'btree' },
    }
})

local ROAD_SKIP = {
    proposed = true,
    construction = true,
    abandoned = true,
    razed = true,
    no = true,
    platform = true,
    corridor = true,
    bus_stop = true,
    rest_area = true,
    services = true,
    elevator = true,
    steps = true,
}

local function is_poi(tags)
    if tags.amenity or tags.shop or tags.office then
        return true
    end
    if tags.building == 'apartments' then
        return true
    end
    if tags.railway == 'station' or tags.railway == 'halt' or tags.railway == 'tram_stop' then
        return true
    end
    if tags.public_transport == 'station' or tags.public_transport == 'stop_position'
        or tags.public_transport == 'platform' then
        return true
    end
    if tags.highway == 'bus_stop' then
        return true
    end
    return false
end

local function is_area(tags)
    return tags.area == 'yes' or tags.area == '1'
end

local function poi_row(object, point, area)
    local tags = object.tags
    return {
        name = tags.name,
        amenity = tags.amenity,
        shop = tags.shop,
        office = tags.office,
        building = tags.building,
        railway = tags.railway,
        public_transport = tags.public_transport,
        highway = tags.highway,
        tags = tags,
        geom = point,
        area_geom = area,
    }
end

function osm2pgsql.process_node(object)
    if is_poi(object.tags) then
        poi:insert(poi_row(object, object:as_point(), nil))
    end
end

function osm2pgsql.process_way(object)
    local tags = object.tags
    if tags.highway and not ROAD_SKIP[tags.highway] and not is_area(tags) then
        local line = object:as_linestring()
        if line ~= nil then
            road:insert({
                name = tags.name,
                highway = tags.highway,
                tags = tags,
                geom = line,
            })
        end
    end
    if not is_poi(tags) then
        return
    end
    if object.is_closed then
        local area = object:as_multipolygon()
        if area ~= nil then
            poi:insert(poi_row(object, area:centroid(), area))
            return
        end
    end
    local line = object:as_linestring()
    if line ~= nil then
        poi:insert(poi_row(object, line:centroid(), nil))
    end
end

function osm2pgsql.process_relation(object)
    local tags = object.tags
    if tags.type == 'route' then
        return
    end
    if not is_poi(tags) then
        return
    end
    local area = object:as_multipolygon()
    if area == nil then
        return
    end
    poi:insert(poi_row(object, area:centroid(), area))
end
