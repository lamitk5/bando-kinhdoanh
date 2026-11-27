import { useEffect, useRef, useState } from "react";
import Chart from "chart.js/auto";
import Map from "ol/Map";
import View from "ol/View";
import TileLayer from "ol/layer/Tile";
import VectorLayer from "ol/layer/Vector";
import Heatmap from "ol/layer/Heatmap";
import OSM from "ol/source/OSM";
import VectorSource from "ol/source/Vector";
import GeoJSON from "ol/format/GeoJSON";
import Feature from "ol/Feature";
import Point from "ol/geom/Point";
import LineString from "ol/geom/LineString";
import { fromLonLat, toLonLat } from "ol/proj";
import { Circle, Fill, Stroke, Style } from "ol/style";
import "ol/ol.css";

const CATEGORIES = [
  ["cafe_drink", "Cà phê / đồ uống"],
  ["pharmacy", "Nhà thuốc"],
  ["convenience", "Cửa hàng tiện lợi"],
  ["education", "Trường học"],
  ["office", "Văn phòng"],
  ["apartments", "Chung cư"],
  ["hospital", "Bệnh viện"],
  ["clinic", "Phòng khám"],
  ["transit_station", "Ga"],
  ["transit_stop", "Điểm dừng"]
];

const BUSINESSES = CATEGORIES.slice(0, 3);
const RENT = 25000000;
const LIST_LABEL = {
  listed_both: "còn ở cả 2024-08-05 và 2026-07-10",
  listed_2026_only: "còn ở bản 2026-07-10",
  listed_2024_only: "chỉ có ở bản 2024-08-05",
  not_in_saved_lists: "không khớp danh sách đã lưu"
};
const COUNT_KEYS = ["motorcycle", "car", "person", "utility_pole", "tree", "sign_blocking_visibility"];

function geodesicCircle(lon, lat, meters) {
  const earth = 6378137;
  const step = meters / earth;
  const lat1 = lat * Math.PI / 180;
  const lon1 = lon * Math.PI / 180;
  const coords = [];
  for (let i = 0; i <= 64; i += 1) {
    const bearing = 2 * Math.PI * i / 64;
    const lat2 = Math.asin(Math.sin(lat1) * Math.cos(step) + Math.cos(lat1) * Math.sin(step) * Math.cos(bearing));
    const lon2 = lon1 + Math.atan2(
      Math.sin(bearing) * Math.sin(step) * Math.cos(lat1),
      Math.cos(step) - Math.sin(lat1) * Math.sin(lat2)
    );
    coords.push(fromLonLat([lon2 * 180 / Math.PI, lat2 * 180 / Math.PI]));
  }
  return new LineString(coords);
}

function ring(lon, lat, meters, color) {
  const feature = new Feature({ geometry: geodesicCircle(lon, lat, meters) });
  feature.setStyle(new Style({ stroke: new Stroke({ color, width: 2 }) }));
  return feature;
}

async function readJson(response) {
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || String(response.status));
  }
  return body;
}

export default function App() {
  const mapElement = useRef(null);
  const mapRef = useRef(null);
  const sources = useRef(null);
  const chartRef = useRef(null);
  const canvasRef = useRef(null);
  const [business, setBusiness] = useState("cafe_drink");
  const [sites, setSites] = useState([]);
  const [countText, setCountText] = useState("");
  const [compareNote, setCompareNote] = useState("");
  const [category, setCategory] = useState("cafe_drink");
  const [placeName, setPlaceName] = useState("");
  const [submitter, setSubmitter] = useState("");
  const [reviewToken, setReviewToken] = useState("");
  const [decisionNote, setDecisionNote] = useState("");
  const [contributionNote, setContributionNote] = useState("");
  const [lastContribution, setLastContribution] = useState(null);
  const [photoNote, setPhotoNote] = useState("");
  const [photoRows, setPhotoRows] = useState([]);
  const [photoExtra, setPhotoExtra] = useState("");
  const [backtestNote, setBacktestNote] = useState("");
  const [backtest, setBacktest] = useState(null);
  const [reference, setReference] = useState(null);
  const [reviewers, setReviewers] = useState(null);
  const [reloadTick, setReloadTick] = useState(0);
  const sitesRef = useRef([]);
  const businessRef = useRef(business);

  useEffect(() => { sitesRef.current = sites; }, [sites]);
  useEffect(() => { businessRef.current = business; }, [business]);

  useEffect(() => {
    const siteSource = new VectorSource();
    const pointSource = new VectorSource();
    const heatSource = new VectorSource();
    const districtSource = new VectorSource();
    sources.current = { siteSource, pointSource, heatSource, districtSource };
    const map = new Map({
      target: mapElement.current,
      layers: [
        new TileLayer({ source: new OSM() }),
        new VectorLayer({
          source: districtSource,
          style: new Style({ stroke: new Stroke({ color: "#1d4ed8", width: 2 }) })
        }),
        new Heatmap({ source: heatSource, blur: 18, radius: 12, weight: feature => feature.get("weight") }),
        new VectorLayer({
          source: pointSource,
          style: feature => new Style({
            image: new Circle({
              radius: 4,
              fill: new Fill({ color: feature.get("role") === "competitor" ? "#b45309" : "#047857" })
            })
          })
        }),
        new VectorLayer({ source: siteSource })
      ],
      view: new View({ center: fromLonLat([105.80, 21.03]), zoom: 12 })
    });
    map.on("click", event => {
      const current = sitesRef.current;
      if (current.length >= 3) return;
      const lonLat = toLonLat(event.coordinate);
      const next = current.concat({ label: "Điểm " + (current.length + 1), lon: lonLat[0], lat: lonLat[1] });
      sitesRef.current = next;
      setSites(next);
    });
    map.updateSize();
    mapRef.current = map;
    fetch("/api/scoring/reference").then(readJson).then(setReference).catch(error => setReference({ matrix_label: error.message }));
    fetch("/api/spatial/reviewers").then(readJson).then(setReviewers).catch(error => setReviewers({ note: error.message, names: [] }));
    return () => map.setTarget(null);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const frame = { west: 105.70, south: 20.96, east: 105.87, north: 21.10 };
    const query = new URLSearchParams({ ...frame, business_type: business });
    Promise.all([
      fetch("/api/map/districts").then(readJson),
      fetch("/api/map/points?" + query).then(readJson),
      fetch("/api/map/heatmap?" + query).then(readJson)
    ]).then(([districts, points, heat]) => {
      if (cancelled || !sources.current) return;
      const { districtSource, pointSource, heatSource } = sources.current;
      districtSource.clear();
      districtSource.addFeatures(new GeoJSON().readFeatures(districts, {
        dataProjection: "EPSG:4326",
        featureProjection: "EPSG:3857"
      }));
      pointSource.clear();
      points.features.forEach(point => {
        pointSource.addFeature(new Feature({
          geometry: new Point(fromLonLat([point.lon, point.lat])),
          role: point.role,
          name: point.name
        }));
      });
      heatSource.clear();
      heat.cells.forEach(cell => {
        if (cell.competitors < 1) return;
        heatSource.addFeature(new Feature({
          geometry: new Point(fromLonLat([cell.lon, cell.lat])),
          weight: Math.min(1, cell.competitors / 8)
        }));
      });
      const cap = points.truncated ? " Danh sách dừng ở " + points.features.length + " điểm." : "";
      setCountText(points.features.length + " điểm. Màu xanh là điểm hút khách, màu nâu là đối thủ của ngành đang chọn." + cap);
    }).catch(error => { if (!cancelled) setCountText(error.message); });
    return () => { cancelled = true; };
  }, [business, reloadTick]);

  useEffect(() => {
    const siteSource = sources.current?.siteSource;
    if (!siteSource) return;
    siteSource.clear();
    sites.forEach(site => {
      siteSource.addFeature(ring(site.lon, site.lat, 200, "#64748b"));
      siteSource.addFeature(ring(site.lon, site.lat, 500, "#0f766e"));
      siteSource.addFeature(ring(site.lon, site.lat, 1000, "#1d4ed8"));
    });
  }, [sites]);

  function comparePayload() {
    return {
      business_type: businessRef.current,
      rent_vnd_per_month: RENT,
      sites: sitesRef.current
    };
  }

  async function compare() {
    try {
      if (sitesRef.current.length < 2) throw new Error("Cần ít nhất 2 điểm");
      const body = await readJson(await fetch("/api/scoring/compare", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(comparePayload())
      }));
      const colors = ["#0f766e", "#b45309", "#1d4ed8"];
      if (chartRef.current) chartRef.current.destroy();
      chartRef.current = new Chart(canvasRef.current, {
        type: "radar",
        data: {
          labels: ["Điểm hút khách", "Bão hòa đối thủ", "Tiếp cận đường"],
          datasets: body.sites.map((site, index) => ({
            label: site.label,
            data: [
              site.components.attractor_density.score,
              site.components.competitor_saturation.score,
              site.components.arterial_access.score
            ],
            borderColor: colors[index]
          }))
        },
        options: { scales: { r: { min: 0, max: 100 } } }
      });
      setCompareNote(body.radar_note + " Tiền thuê 25.000.000 đồng/tháng là mốc chung, không phải giá quan sát. Chưa có doanh thu nên đây không phải ROI.");
    } catch (error) {
      setCompareNote(error.message || "Không so sánh được");
    }
  }

  async function downloadPdf() {
    if (sitesRef.current.length < 2) return;
    const response = await fetch("/api/scoring/compare.pdf", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(comparePayload())
    });
    const blob = await response.blob();
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "so-sanh.pdf";
    link.click();
  }

  async function submitPlace() {
    const chosen = sitesRef.current.at(-1);
    try {
      const body = await readJson(await fetch("/api/spatial/contributions", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: placeName,
          category,
          lat: chosen ? chosen.lat : 21.037861,
          lon: chosen ? chosen.lon : 105.801001,
          submitter
        })
      }));
      setLastContribution(body);
      setContributionNote("Đã nhận dòng " + body.id + ", trạng thái " + body.status + ". Chưa tính vào điểm.");
    } catch (error) {
      setContributionNote(error.message || "Không gửi được");
    }
  }

  async function decide(path, payload, success) {
    if (!lastContribution) {
      setContributionNote("Chưa có dòng chờ.");
      return;
    }
    const response = await fetch("/api/spatial/contributions/" + lastContribution.id + path, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Reviewer-Token": reviewToken },
      body: JSON.stringify(payload)
    });
    const body = await response.json();
    setContributionNote(response.ok ? success(body) : (body.message || "Không duyệt được"));
  }

  function renderPhoto(job) {
    const result = job.result || {};
    const advisory = result.advisory || {};
    setPhotoNote("Trạng thái " + job.status
      + ". Tư vấn: " + (advisory.status || "không có")
      + ". Ảnh đã gửi lên mạng: " + (advisory.image_sent === false ? "không" : "không rõ")
      + ". " + (advisory.text || result.yolo_note || advisory.reason || job.error_message || ""));
    const yolo = result.yolo;
    if (!yolo) {
      setPhotoRows([]);
      setPhotoExtra("");
      return;
    }
    const requested = yolo.requested || {};
    setPhotoRows(COUNT_KEYS.map(key => ({
      key,
      value: requested[key] === null || requested[key] === undefined ? "trống" : requested[key]
    })));
    const counts = yolo.counts || {};
    const widthText = result.sidewalk_width_m === null || result.sidewalk_width_m === undefined
      ? "không gán (" + (result.recommendation || "chưa đủ mặt tiền/vỉa hè lộ rõ") + ")"
      : result.sidewalk_width_m + " m";
    setPhotoExtra("Phân rã ô tô (car " + counts.car + ", bus " + counts.bus + ", truck " + counts.truck
      + "), người lái xe hai bánh (rider) " + (counts.rider || 0)
      + ", đèn giao thông " + counts.traffic_light + ", stop_sign " + counts.stop_sign
      + ", biển báo Cityscapes " + counts.traffic_sign + ", biển treo " + counts.hanging_sign
      + ". Bề rộng vỉa hè: " + widthText
      + ". Tầm nhìn chưa đo nên không trộn vào điểm ngành."
      + " Thời gian suy luận YOLO " + yolo.inference_ms + " ms (tổng " + yolo.wall_ms + " ms) trên " + yolo.device
      + ". " + (yolo.note || ""));
  }

  function countPhoto(file) {
    if (!file) {
      setPhotoNote("Chưa chọn ảnh.");
      return;
    }
    const body = new FormData();
    body.append("image", file);
    setPhotoNote("Đang đếm trên máy này.");
    setPhotoRows([]);
    setPhotoExtra("");
    fetch("/api/vision/jobs", { method: "POST", body }).then(readJson).then(created => {
      const wait = () => fetch("/api/vision/jobs/" + created.id).then(readJson).then(job => {
        if (job.status === "queued" || job.status === "running") {
          setPhotoNote("Trạng thái " + job.status + ".");
          setTimeout(wait, 700);
          return;
        }
        renderPhoto(job);
      });
      wait();
    }).catch(error => setPhotoNote(error.message));
  }

  async function runBacktest() {
    setBacktestNote("Đang chấm các điểm...");
    try {
      const body = await readJson(await fetch("/api/backtest"));
      const former = (body.former_places || []).map(place =>
        place.district + " " + place.lifecycle_key + "=" + place.lifecycle_value + " điểm " + place.spatial_score
      ).join("; ");
      const premiseMedians = (body.open_premises_by_type || []).map(item =>
        item.business_type + " " + item.count + " cơ sở, trung vị " + item.median_spatial_score_without_self
      ).join("; ");
      setBacktestNote(body.limitation + " " + body.training_rejection + " "
        + body.sourced_note + " " + body.question + " " + (body.panel_finding || "")
        + " " + (body.unlocated_note || "")
        + " Trung vị nhóm không còn là " + body.closed_group_median_spatial_score
        + ". Trung vị nhóm Circle K còn trong danh sách hãng là " + body.brand_listed_median_spatial_score + "."
        + " " + (body.open_premises_note || "")
        + (premiseMedians ? " " + premiseMedians + "." : "")
        + " Circle K còn khớp danh sách 2026-07-10: " + body.open_premises_still_on_brand_list_count + "."
        + (former ? " Điểm từng mang thẻ tiện lợi: " + former + "." : ""));
      setBacktest(body);
    } catch (error) {
      setBacktestNote(error.message);
    }
  }

  return (
    <div className="min-h-screen bg-stone-50 text-stone-900">
      <header className="border-b border-stone-200 px-4 py-3">
        <strong>Bốn quận nội thành</strong>
        <span className="ml-3 text-sm text-stone-600">Điểm hút khách và đối thủ lấy từ OpenStreetMap đã nạp. Vòng tròn là 200, 500 và 1000 m.</span>
      </header>
      <main className="grid grid-cols-1 lg:h-[calc(100vh-58px)] lg:grid-cols-[1.4fr_0.8fr] lg:overflow-hidden">
        <div ref={mapElement} className="h-[420px] lg:h-full" />
        <aside className="overflow-auto border-t border-stone-200 p-4 text-[15px] lg:h-full lg:border-l lg:border-t-0">
          <label className="mt-2 block" htmlFor="business">Ngành</label>
          <select id="business" className="mt-1 border border-stone-300 bg-white px-2 py-1" value={business} onChange={event => setBusiness(event.target.value)}>
            {BUSINESSES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={() => setReloadTick(value => value + 1)}>Tải lại điểm</button>
          <p className="mt-2 text-sm text-stone-600">{countText}</p>

          <h2 className="mt-4 text-lg font-semibold">Giới hạn còn thiếu số thật</h2>
          <p className="text-sm text-stone-600">
            {reference?.matrix_label || "Đang đọc ma trận."} Chưa có phiếu chuyên gia nên trọng số không đổi. Chưa có doanh thu nên không có ROI. Tầm nhìn chưa có nhãn ảnh Hà Nội nên không trộn vào điểm ngành. API đám mây chưa gắn và không nhận ảnh.
          </p>

          <h2 className="mt-4 text-lg font-semibold">So sánh 2–3 mặt bằng</h2>
          <p className="text-sm text-stone-600">Bấm trên bản đồ để thêm một điểm. Tối đa 3 điểm.</p>
          <ol className="list-decimal pl-5">
            {sites.map(site => <li key={site.label}>{site.label} ({site.lat.toFixed(5)}, {site.lon.toFixed(5)})</li>)}
          </ol>
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={() => setSites([])}>Xóa điểm</button>
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={compare}>So sánh</button>
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={downloadPdf}>Tải PDF</button>
          <canvas ref={canvasRef} className="mt-3 block h-56 w-full" height="220" />
          <p className="text-sm text-stone-600">{compareNote}</p>

          <h2 className="mt-4 text-lg font-semibold">Đóng góp điểm</h2>
          <p className="text-sm text-stone-600">Điểm mới ở trạng thái chờ. {reviewers?.note || "Đang đọc tài khoản người duyệt."}</p>
          {(reviewers?.names || []).length > 0 && <p className="text-sm text-stone-600">Đang cấu hình: {reviewers.names.join(", ")}.</p>}
          <label className="mt-2 block" htmlFor="place-category">Hạng mục</label>
          <select id="place-category" className="mt-1 border border-stone-300 bg-white px-2 py-1" value={category} onChange={event => setCategory(event.target.value)}>
            {CATEGORIES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          <label className="mt-2 block" htmlFor="place-name">Tên điểm</label>
          <input id="place-name" className="mt-1 border border-stone-300 px-2 py-1" value={placeName} onChange={event => setPlaceName(event.target.value)} />
          <label className="mt-2 block" htmlFor="place-submitter">Người gửi</label>
          <input id="place-submitter" className="mt-1 border border-stone-300 px-2 py-1" value={submitter} onChange={event => setSubmitter(event.target.value)} />
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={submitPlace}>Gửi để chờ duyệt</button>
          <label className="mt-2 block" htmlFor="review-token">Mã của đúng người duyệt</label>
          <input id="review-token" className="mt-1 border border-stone-300 px-2 py-1" type="password" value={reviewToken} onChange={event => setReviewToken(event.target.value)} />
          <label className="mt-2 block" htmlFor="decision-note">Lý do nếu từ chối</label>
          <input id="decision-note" className="mt-1 border border-stone-300 px-2 py-1" value={decisionNote} onChange={event => setDecisionNote(event.target.value)} />
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={() => decide("/approve", {}, body => "Đã duyệt bởi " + body.reviewer + ". Điểm này được tính.")}>Duyệt dòng vừa gửi</button>
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={() => decide("/reject", { decision_note: decisionNote }, () => "Đã từ chối. Dòng không được tính.")}>Từ chối dòng vừa gửi</button>
          <p className="text-sm text-stone-600">{contributionNote}</p>

          <h2 className="mt-4 text-lg font-semibold">Đếm vật thể trên ảnh</h2>
          <p className="text-sm text-stone-600">Ảnh đi qua YOLOv8m đa tỷ lệ, YOLO-World, SegFormer Cityscapes và Depth-Anything-V2 ngay trên máy này. Chỉ gán bề rộng vỉa hè khi kiểm định cảnh có mặt tiền công trình và mặt nền lộ rõ. Ảnh không được gửi lên mạng. API đám mây chưa được gắn.</p>
          <input className="mt-2 block" type="file" accept="image/jpeg,image/png,image/webp" onChange={event => countPhoto(event.target.files[0])} />
          <p className="text-sm text-stone-600">{photoNote}</p>
          {photoRows.length > 0 && (
            <table className="mt-3 w-full border-collapse text-[13px]">
              <thead><tr><th className="border-b border-stone-200 px-1 py-1 text-left">Hạng</th><th className="border-b border-stone-200 px-1 py-1 text-left">Số đếm</th></tr></thead>
              <tbody>
                {photoRows.map(row => <tr key={row.key}><td className="border-b border-stone-200 px-1 py-1">{row.key}</td><td className="border-b border-stone-200 px-1 py-1">{row.value}</td></tr>)}
              </tbody>
            </table>
          )}
          {photoExtra && <p className="text-sm text-stone-600">{photoExtra}</p>}

          <h2 className="mt-4 text-lg font-semibold">Kiểm chứng vị trí</h2>
          <button className="mr-2 mt-2 border border-stone-300 bg-white px-2 py-1" type="button" onClick={runBacktest}>Chạy trên dữ liệu đã nạp</button>
          <p className="text-sm text-stone-600">{backtestNote}</p>
          {backtest && <BacktestTables body={backtest} />}
        </aside>
      </main>
    </div>
  );
}

function BacktestTables({ body }) {
  return (
    <>
      <DataTable headers={["Ngành", "Cơ sở có tên", "Trung vị, đã trừ chính nó", "Khoảng một phần tư", "Trung vị 60 điểm lưới", "Có ngày xem"]}>
        {body.businesses.map(item => (
          <tr key={item.business_type}>
            <Td>{item.business_type}</Td>
            <Td>{item.named_count}</Td>
            <Td>{item.named_median_spatial_score_without_self}</Td>
            <Td>{item.named_lower_quartile}–{item.named_upper_quartile}</Td>
            <Td>{item.grid_median_spatial_score}</Td>
            <Td>{item.surveyed_count}</Td>
          </tr>
        ))}
      </DataTable>
      <DataTable caption="Còn mặt bằng trên bản đồ" headers={["Cơ sở", "Địa chỉ", "Quận", "Ngành", "Điểm, đã trừ chính nó", "Circle K trong danh sách đã lưu"]}>
        {(body.open_premises || []).map(place => (
          <tr key={place.osm_url}>
            <Td><a className="underline" href={place.osm_url}>{place.name || place.brand || "không tên"}</a></Td>
            <Td>{place.housenumber} {place.street}</Td>
            <Td>{place.district}</Td>
            <Td>{place.business_type}</Td>
            <Td>{place.spatial_score_without_self}</Td>
            <Td>{place.circle_k_list ? (LIST_LABEL[place.circle_k_list] || place.circle_k_list) : ""}</Td>
          </tr>
        ))}
      </DataTable>
      <DataTable headers={["Điểm có nguồn", "Quận", "Nhóm", "Điểm để so"]}>
        {(body.sourced_places || []).map(place => (
          <tr key={(place.osm_url || place.source_url) + place.outcome}>
            <Td><a className="underline" href={place.osm_url || place.source_url}>{place.name}</a></Td>
            <Td>{place.district}</Td>
            <Td>{place.outcome}</Td>
            <Td>{place.spatial_score_for_compare}</Td>
          </tr>
        ))}
      </DataTable>
      <DataTable headers={["Có nguồn, không chấm", "Địa chỉ trên bản đồ", "Quận", "Node khớp tên", "Vì sao không chấm"]}>
        {(body.unlocated_closures || []).map(place => (
          <tr key={place.source_url}>
            <Td><a className="underline" href={place.source_url}>{place.name}</a></Td>
            <Td>{place.address}</Td>
            <Td>{place.district}</Td>
            <Td>{place.osm_url ? <a className="underline" href={place.osm_url}>mở</a> : "không có"}</Td>
            <Td>{place.reason_unscored}</Td>
          </tr>
        ))}
      </DataTable>
      <DataTable headers={["Thương hiệu có số nhà", "Ngành", "Số cơ sở", "Trung vị, đã trừ chính nó"]}>
        {(body.benchmark_brands || []).filter(brand => brand.count >= 3).map(brand => (
          <tr key={brand.brand + brand.business_type}>
            <Td>{brand.brand}</Td>
            <Td>{brand.business_type}</Td>
            <Td>{brand.count}</Td>
            <Td>{brand.median_spatial_score_without_self}</Td>
          </tr>
        ))}
      </DataTable>
    </>
  );
}

function DataTable({ caption, headers, children }) {
  return (
    <table className="mt-3 w-full border-collapse text-[13px]">
      {caption && <caption className="px-1 py-2 text-left font-semibold">{caption}</caption>}
      <thead>
        <tr>{headers.map(header => <th key={header} className="border-b border-stone-200 px-1 py-1 text-left">{header}</th>)}</tr>
      </thead>
      <tbody>{children}</tbody>
    </table>
  );
}

function Td({ children }) {
  return <td className="border-b border-stone-200 px-1 py-1 text-left">{children}</td>;
}
