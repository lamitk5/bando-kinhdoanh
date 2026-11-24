#!/usr/bin/env python3
"""Huấn luyện mô hình dự đoán bề rộng vỉa hè, mét, từ ảnh.

Nhãn là cột width trong Seoul Sidewalk Accessibility Image Dataset
(10.5281/zenodo.22908353, CC0). Cột filename ghi đuôi HEIC; file trong zip là JPG cùng tên gốc.
Ảnh không nằm trong git.

Tách mẫu theo SHA-256 của đúng chuỗi trong cột filename, trước khi xem sai số:
- đuôi 0 và 1: kiểm tra
- đuôi 2: chọn mô hình
- đuôi 3 đến 9: học

Đặc trưng là trung bình từng ô của ảnh xám. Xám = (299R + 587G + 114B) / 1000.
Hai cỡ ô được chốt trước: 32×24 và 48×36. Mỗi cỡ có hồi quy ridge và mạng một lớp ẩn 16 nút.
Ứng viên nào có sai số tuyệt đối trung bình thấp hơn trên tập chọn thì được giữ.
Tập kiểm tra chỉ được tính một lần sau khi đã chọn. Trọng số Qwen không đổi.
Tập này không có nhãn tầm nhìn.

Mức thứ tự là quy ước mét, không phải nhãn người chấm: dưới 0,90 là none, dưới 1,50 là narrow,
dưới 3,00 là enough, còn lại là wide. Độ khớp mức chỉ được tính sau khi mô hình đã chọn.
"""

from __future__ import annotations

import csv
import hashlib
import json
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
from pillow_heif import register_heif_opener

register_heif_opener()

ROOT = Path("/workspace/data/raw/sidewalk-seoul")
CSV_PATH = ROOT / "summary_attributes.csv"
OUT_PATH = Path("/workspace/backend/src/main/resources/vision/sidewalk_width_model.json")
GRIDS = ((32, 24), (48, 36))
BANDS = (0.90, 1.50, 3.00)
ALPHAS = (0.1, 1.0, 10.0, 100.0, 1000.0, 10000.0)
HIDDEN = 16
MLP_EPOCHS = 300
MLP_LR = 0.05
SEED = 0
EXPECTED_MD5 = {
    "imagery_1.zip": "220880dafcbaceb4df1d27e12ada2687",
    "imagery_2.zip": "850ad84eca1860cca862747f45bb8e0d",
    "imagery_3.zip": "84fceef93e2980a60f1998786ccc51a5",
}


def bucket(name: str) -> int:
    digest = hashlib.sha256(name.encode()).hexdigest()
    return int(digest[:8], 16) % 10


def split_of(name: str) -> str:
    group = bucket(name)
    if group <= 1:
        return "test"
    if group == 2:
        return "val"
    return "train"


def load_labels() -> list[tuple[str, float]]:
    with CSV_PATH.open(newline="") as handle:
        rows = list(csv.DictReader(handle))
    labels = []
    for row in rows:
        name = row.get("filename") or row.get("\ufefffilename")
        labels.append((name, float(row["width"])))
    if len(labels) != 514:
        raise SystemExit(f"CSV phải có 514 dòng nhãn, đang có {len(labels)}")
    return labels


def file_md5(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def extract_images() -> None:
    for name, expected in EXPECTED_MD5.items():
        path = ROOT / name
        if not path.exists():
            raise SystemExit(f"Thiếu {path}")
        digest = file_md5(path)
        if digest != expected:
            raise SystemExit(f"{name} có MD5 {digest}, không khớp bản Zenodo")
        with zipfile.ZipFile(path) as archive:
            archive.extractall(ROOT / "images")


def index_files() -> dict[str, Path]:
    found: dict[str, Path] = {}
    for path in (ROOT / "images").rglob("*"):
        if "__MACOSX" in path.parts or path.name.startswith("."):
            continue
        if path.suffix.lower() not in {".jpg", ".jpeg", ".png", ".heic"}:
            continue
        found[path.stem] = path
    return found


def block_gray(path: Path, grids: tuple[tuple[int, int], ...]) -> list[np.ndarray]:
    with Image.open(path) as image:
        rgb = np.asarray(ImageOps.exif_transpose(image).convert("RGB"), dtype=np.float64)
    gray = (299.0 * rgb[:, :, 0] + 587.0 * rgb[:, :, 1] + 114.0 * rgb[:, :, 2]) / 1000.0
    height, width = gray.shape
    features = []
    for grid_width, grid_height in grids:
        out = np.empty(grid_height * grid_width, dtype=np.float64)
        index = 0
        for y in range(grid_height):
            y0 = y * height // grid_height
            y1 = max(y0 + 1, (y + 1) * height // grid_height)
            for x in range(grid_width):
                x0 = x * width // grid_width
                x1 = max(x0 + 1, (x + 1) * width // grid_width)
                out[index] = gray[y0:y1, x0:x1].mean() / 255.0
                index += 1
        features.append(out)
    return features


def band(width: float) -> str:
    if width < BANDS[0]:
        return "none"
    if width < BANDS[1]:
        return "narrow"
    if width < BANDS[2]:
        return "enough"
    return "wide"


def standardize(train: np.ndarray, other: list[np.ndarray]) -> tuple[np.ndarray, list[np.ndarray], np.ndarray, np.ndarray]:
    mean = train.mean(axis=0)
    std = train.std(axis=0)
    std = np.where(std < 1e-6, 1.0, std)
    scaled = [(block - mean) / std for block in [train, *other]]
    return scaled[0], scaled[1:], mean, std


def mae(pred: np.ndarray, target: np.ndarray) -> float:
    return float(np.mean(np.abs(pred - target)))


def fit_ridge(x: np.ndarray, y: np.ndarray, alpha: float) -> np.ndarray:
    gram = x.T @ x
    gram.flat[:: x.shape[1] + 1] += alpha
    return np.linalg.solve(gram, x.T @ y)


def train_mlp(x: np.ndarray, y: np.ndarray, x_val: np.ndarray, y_val: np.ndarray) -> dict:
    rng = np.random.default_rng(SEED)
    w1 = rng.normal(0, 0.02, size=(HIDDEN, x.shape[1]))
    b1 = np.zeros(HIDDEN)
    w2 = rng.normal(0, 0.02, size=HIDDEN)
    b2 = 0.0
    m1 = np.zeros_like(w1)
    v1 = np.zeros_like(w1)
    mb1 = np.zeros_like(b1)
    vb1 = np.zeros_like(b1)
    m2 = np.zeros_like(w2)
    v2 = np.zeros_like(w2)
    mb2 = 0.0
    vb2 = 0.0
    best = None
    best_mae = None
    beta1, beta2, eps = 0.9, 0.999, 1e-8
    for step in range(1, MLP_EPOCHS + 1):
        hidden = np.maximum(0, x @ w1.T + b1)
        pred = hidden @ w2 + b2
        error = (pred - y) / len(y)
        dw2 = hidden.T @ error
        db2 = float(error.sum())
        back = error[:, None] * w2
        back *= hidden > 0
        dw1 = back.T @ x
        db1 = back.sum(axis=0)
        m1, v1, w1 = adam(m1, v1, w1, dw1, step, beta1, beta2, eps)
        mb1, vb1, b1 = adam(mb1, vb1, b1, db1, step, beta1, beta2, eps)
        m2, v2, w2 = adam(m2, v2, w2, dw2, step, beta1, beta2, eps)
        mb2 = beta1 * mb2 + (1 - beta1) * db2
        vb2 = beta2 * vb2 + (1 - beta2) * db2 * db2
        b2 -= MLP_LR * (mb2 / (1 - beta1**step)) / (np.sqrt(vb2 / (1 - beta2**step)) + eps)
        val_pred = np.maximum(0, x_val @ w1.T + b1) @ w2 + b2
        score = mae(val_pred, y_val)
        if best_mae is None or score < best_mae:
            best_mae = score
            best = {
                "w1": w1.copy(),
                "b1": b1.copy(),
                "w2": w2.copy(),
                "b2": b2,
                "val_mae_m": score,
                "epoch": step,
            }
    return best


def adam(moment, second, value, grad, step, beta1, beta2, eps):
    moment = beta1 * moment + (1 - beta1) * grad
    second = beta2 * second + (1 - beta2) * grad * grad
    update = (moment / (1 - beta1**step)) / (np.sqrt(second / (1 - beta2**step)) + eps)
    return moment, second, value - MLP_LR * update


def predict_ridge(x: np.ndarray, weights: np.ndarray, center: float) -> np.ndarray:
    return x @ weights + center


def predict_mlp(x: np.ndarray, model: dict, center: float) -> np.ndarray:
    hidden = np.maximum(0, x @ model["w1"].T + model["b1"])
    return hidden @ model["w2"] + model["b2"] + center


def main() -> None:
    labels = load_labels()
    files = index_files()
    if any(Path(name).stem not in files for name, _ in labels):
        extract_images()
        files = index_files()
    missing = [name for name, _ in labels if Path(name).stem not in files]
    if missing:
        raise SystemExit(f"Thiếu {len(missing)} ảnh, ví dụ {missing[:3]}")
    names, targets = [], []
    grids = [[] for _ in GRIDS]
    for name, width in labels:
        names.append(name)
        targets.append(width)
        for index, features in enumerate(block_gray(files[Path(name).stem], GRIDS)):
            grids[index].append(features)
    y = np.array(targets, dtype=np.float64)
    groups = np.array([split_of(name) for name in names])
    train = groups == "train"
    val = groups == "val"
    test = groups == "test"
    y_center = float(y[train].mean())
    y_train = y[train] - y_center
    y_val = y[val] - y_center

    candidates = []
    for grid, rows in zip(GRIDS, grids, strict=True):
        x = np.vstack(rows)
        x_train, (x_val, x_test), mean, std = standardize(x[train], [x[val], x[test]])
        ridge_choice = None
        for alpha in ALPHAS:
            weights = fit_ridge(x_train, y_train, alpha)
            score = mae(predict_ridge(x_val, weights, 0), y_val)
            if ridge_choice is None or score < ridge_choice["val_mae_m"]:
                ridge_choice = {"alpha": alpha, "weights": weights, "val_mae_m": score}
        mlp_choice = train_mlp(x_train, y_train, x_val, y_val)
        candidates.append({
            "grid": grid,
            "x_train": x_train,
            "x_val": x_val,
            "x_test": x_test,
            "mean": mean,
            "std": std,
            "ridge": ridge_choice,
            "mlp": mlp_choice,
        })
    chosen = min(
        candidates,
        key=lambda item: min(item["ridge"]["val_mae_m"], item["mlp"]["val_mae_m"]),
    )
    ridge_choice = chosen["ridge"]
    mlp_choice = chosen["mlp"]
    selected = "ridge" if ridge_choice["val_mae_m"] <= mlp_choice["val_mae_m"] else "mlp"
    if selected == "ridge":
        train_pred = predict_ridge(chosen["x_train"], ridge_choice["weights"], y_center)
        val_pred = predict_ridge(chosen["x_val"], ridge_choice["weights"], y_center)
        test_pred = predict_ridge(chosen["x_test"], ridge_choice["weights"], y_center)
    else:
        train_pred = predict_mlp(chosen["x_train"], mlp_choice, y_center)
        val_pred = predict_mlp(chosen["x_val"], mlp_choice, y_center)
        test_pred = predict_mlp(chosen["x_test"], mlp_choice, y_center)
    mean = chosen["mean"]
    std = chosen["std"]
    baseline = np.full(test.sum(), y_center)
    test_mae = mae(test_pred, y[test])
    baseline_mae = mae(baseline, y[test])
    true_bands = [band(value) for value in y[test]]
    pred_bands = [band(value) for value in test_pred]
    band_hits = sum(left == right for left, right in zip(true_bands, pred_bands, strict=True))
    payload = {
        "format": 2,
        "dataset": "Seoul Sidewalk Accessibility Image Dataset",
        "doi": "10.5281/zenodo.22908353",
        "license": "CC0-1.0",
        "target": "width_m field-measured",
        "features": "block-average gray, (299R+587G+114B)/1000, then /255",
        "resize": [chosen["grid"][0], chosen["grid"][1]],
        "band_cuts_m": list(BANDS),
        "band_rule": "Quy ước mét: dưới 0,90 none, dưới 1,50 narrow, dưới 3,00 enough, còn lại wide. Không phải nhãn người chấm.",
        "test_band_accuracy": band_hits / int(test.sum()),
        "selected": selected,
        "train_count": int(train.sum()),
        "val_count": int(val.sum()),
        "test_count": int(test.sum()),
        "target_mean_m": y_center,
        "feature_mean": mean.tolist(),
        "feature_std": std.tolist(),
        "ridge": {
            "alpha": ridge_choice["alpha"],
            "val_mae_m": ridge_choice["val_mae_m"],
            "weights": ridge_choice["weights"].tolist(),
        },
        "mlp": {
            "hidden": HIDDEN,
            "epochs": MLP_EPOCHS,
            "learning_rate": MLP_LR,
            "seed": SEED,
            "chosen_epoch": mlp_choice["epoch"],
            "val_mae_m": mlp_choice["val_mae_m"],
            "w1": mlp_choice["w1"].tolist(),
            "b1": mlp_choice["b1"].tolist(),
            "w2": mlp_choice["w2"].tolist(),
            "b2": mlp_choice["b2"],
        },
        "train_mae_m": mae(train_pred, y[train]),
        "val_mae_m": mae(val_pred, y[val]),
        "test_mae_m": test_mae,
        "baseline_test_mae_m": baseline_mae,
        "beats_baseline": bool(test_mae < baseline_mae),
        "qwen_weights_updated": False,
        "visibility_trained": False,
        "domain": "Ảnh vỉa hè Seoul, không phải mặt tiền bốn quận Hà Nội.",
    }
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(payload, ensure_ascii=False))
    print(
        json.dumps(
            {
                "selected": selected,
                "counts": payload["train_count"],
                "val": payload["val_count"],
                "test": payload["test_count"],
                "train_mae_m": round(payload["train_mae_m"], 4),
                "val_mae_m": round(payload["val_mae_m"], 4),
                "resize": payload["resize"],
                "test_mae_m": round(payload["test_mae_m"], 4),
                "baseline_test_mae_m": round(baseline_mae, 4),
                "test_band_accuracy": round(payload["test_band_accuracy"], 4),
                "beats_baseline": payload["beats_baseline"],
                "ridge_val_mae_m": round(ridge_choice["val_mae_m"], 4),
                "mlp_val_mae_m": round(mlp_choice["val_mae_m"], 4),
                "candidates": [
                    {
                        "resize": [item["grid"][0], item["grid"][1]],
                        "ridge_val_mae_m": round(item["ridge"]["val_mae_m"], 4),
                        "mlp_val_mae_m": round(item["mlp"]["val_mae_m"], 4),
                    }
                    for item in candidates
                ],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    sys.exit(main())
