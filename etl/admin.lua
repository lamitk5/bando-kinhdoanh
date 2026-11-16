-- Nạp ranh giới hành chính từ relation OSM.
-- Không phân loại POI ở file này. Hình học lưu EPSG:4326.

local admin = osm2pgsql.define_table({
    name = 'raw_admin',
    ids = { type = 'relation', id_column = 'osm_id' },
    columns = {
        { column = 'name', type = 'text' },
        { column = 'name_vi', type = 'text' },
        { column = 'admin_level', type = 'int4' },
        { column = 'tags', type = 'jsonb' },
        { column = 'geom', type = 'multipolygon', projection = 4326, not_null = true },
    },
    indexes = {
        { column = 'geom', method = 'gist' },
        { column = 'admin_level', method = 'btree' },
    }
})

local function tags_or_empty(tags)
    if tags == nil then
        return {}
    end
    return tags
end

function osm2pgsql.process_relation(object)
    local tags = object.tags
    if tags.boundary ~= 'administrative' then
        return
    end
    local level = tonumber(tags.admin_level)
    -- Quận/huyện là admin_level 6. Level 4 chỉ để kiểm tra quận nằm trong Hà Nội.
    if level ~= 4 and level ~= 6 then
        return
    end
    local geom = object:as_multipolygon()
    if geom == nil then
        return
    end
    admin:insert({
        name = tags.name,
        name_vi = tags['name:vi'],
        admin_level = level,
        tags = tags_or_empty(tags),
        geom = geom,
    })
end
