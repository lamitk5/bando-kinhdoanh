-- Bốn quận trong đề tài, lưu trên OSM là boundary=historic, was:admin_level=6
-- tại ảnh PBF này. Không nhận phường hiện hành chỉ vì tên chứa "Cầu Giấy" hay "Đống Đa".

local wanted = {
    ['Quận Cầu Giấy'] = true,
    ['Quận Đống Đa'] = true,
    ['Quận Thanh Xuân'] = true,
    ['Quận Bắc Từ Liêm'] = true,
}

local district = osm2pgsql.define_table({
    name = 'raw_historic_district',
    ids = { type = 'relation', id_column = 'osm_id' },
    columns = {
        { column = 'name', type = 'text', not_null = true },
        { column = 'boundary', type = 'text', not_null = true },
        { column = 'was_admin_level', type = 'int4', not_null = true },
        { column = 'tags', type = 'jsonb' },
        { column = 'geom', type = 'multipolygon', projection = 4326, not_null = true },
    },
    indexes = {
        { column = 'geom', method = 'gist' },
    }
})

function osm2pgsql.process_relation(object)
    local tags = object.tags
    if tags.boundary ~= 'historic' or tags['was:admin_level'] ~= '6' or not wanted[tags.name] then
        return
    end
    local geom = object:as_multipolygon()
    if geom == nil then
        return
    end
    district:insert({
        name = tags.name,
        boundary = tags.boundary,
        was_admin_level = 6,
        tags = tags,
        geom = geom,
    })
end
