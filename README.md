# 藍牙點名系統 (MCU Bluetooth Attendance System)

## 📋 項目概述

這是一個基於 **Bluetooth Low Energy (BLE)** 技術的課堂點名系統，專為大學課堂設計。系統採用雙角色架構：
- **教師端**：發送掃描信號、記錄學生點名、導出出席紀錄
- **學生端**：廣播身份信息、自動簽到

本項目為大學專題研究，仍在開發中。

---

## 🎯 核心功能

### 🧑‍🏫 教師功能
- **學生掃描**：實時掃描周圍學生藍牙設備
- **熱力圖展示**：可視化顯示教室內學生位置分佈
- **出席記錄**：自動記錄學生簽到時間和設備信息
- **導出 CSV**：將出席紀錄導出為 Excel 可讀的 CSV 格式
- **回到選擇界面**：支持切換教師/學生身份

### 👨‍🎓 學生功能
- **自動廣播**：輸入學號/姓名後自動通過藍牙廣播身份信息
- **安全驗證**：採用時間窗口 + Hash驗證機制防止欺騙
- **保存信息**：自動保存最後輸入的學號/姓名（下次使用自動帶入）
- **熱力圖查看**：查看自己在教室內的相對位置

### 🗺️ 熱力圖功能
- **實時位置追蹤**：基於信號強度 (RSSI) 估算設備距離
- **卡爾曼濾波**：平滑化信號噪聲，提高定位精度
- **自動過期清理**：10 秒無信號自動移除該設備
- **學生計數**：顯示當前在線學生數

---

## 🏗️ 項目架構

```
bluetooth/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/mcu/bluetooth/
│   │   │   │   ├── MainActivity.kt           # 主要界面（教師/學生通用）
│   │   │   │   ├── RoleSelectionActivity.kt  # 角色選擇界面
│   │   │   │   ├── HeatmapActivity.kt        # 熱力圖界面
│   │   │   │   ├── HeatmapView.kt            # 熱力圖繪製組件
│   │   │   │   └── KalmanFilter.kt           # 卡爾曼濾波器
│   │   │   └── res/
│   │   │       ├── layout/                   # UI 布局文件
│   │   │       └── values/                   # 資源常數
│   │   ├── test/
│   │   │   └── java/                         # 單元測試
│   │   └── androidTest/
│   │       └── java/                         # 集成測試
│   └── build.gradle.kts                      # 項目依賴配置
├── build.gradle.kts                          # 根項目配置
└── settings.gradle.kts                       # Gradle 設置
```

### 核心類說明

| 類名 | 職責 | 備註 |
|------|------|------|
| **MainActivity** | 主要邏輯，負責藍牙掃描/廣播、消息處理 | ~400 行代碼 |
| **RoleSelectionActivity** | 用戶角色選擇界面 | 簡單的路由 |
| **HeatmapActivity** | 熱力圖展示界面邏輯 | 掃描 + 位置更新 |
| **HeatmapView** | 自定義 View，繪製熱力圖 | Canvas 繪圖 |
| **KalmanFilter** | 卡爾曼濾波器實現 | 平滑 RSSI 數據 |

---

## 🛠️ 技術棧

- **語言**：Kotlin 100%
- **最低 Android 版本**：API 21 (Android 5.0)
- **藍牙技術**：BLE (Bluetooth Low Energy)
- **安全機制**：時間窗口 + SHA-1 Hash
- **構建工具**：Gradle with Kotlin DSL

### 關鍵依賴
- AndroidX AppCompat
- ConstraintLayout
- Android Bluetooth API

---

## 🚀 快速開始

### 環境要求
- Android Studio Arctic Fox (2020.3.1) 或更新版本
- Kotlin 插件 1.5.0+
- 目標 Android 設備 API 21+

### 安裝步驟

1. **Clone項目**
```bash
git clone https://github.com/fygmuf5/bluetooth.git
cd bluetooth
```

2. **用 Android Studio 打開**
```bash
# 方法 1：直接打開項目文件夾
# Android Studio → Open → 選擇 bluetooth 文件夾

# 方法 2：命令行構建
./gradlew assembleDebug
```

3. **安裝到設備**
```bash
./gradlew installDebug
```

4. **運行應用**
- 在 Android Studio 中點擊 "Run" 或使用命令：
```bash
./gradlew runDebug
```

---

## 📱 APP使用指南

### 教師端使用流程

#### 1️⃣ **啟動應用**
- 打開應用後選擇 **"教師"** 按鈕
- 等待藍牙初始化完成

#### 2️⃣ **掃描學生**
- 打開 **"掃描切換開關"**（SCAN 按鈕）
- 應用開始掃描周圍藍牙設備
- 已簽到學生列表自動更新

#### 3️⃣ **查看熱力圖**
- 點擊 **"查看熱力圖"** 按鈕
- 可視化看到教室內學生位置分佈
- 實時更新學生人數和位置

#### 4️⃣ **導出出席紀錄**
- 點擊 **"導出 CSV"** 按鈕
- 自動生成格式：`點名紀錄_YYYYMMDD_HHmm.csv`
- 文件保存到手機下載文件夾

#### 5️⃣ **返回角色選擇**
- 點擊 **"回到選擇身分"** 按鈕
- 返回到初始角色選擇界面

### 學生端使用流程

#### 1️⃣ **啟動應用**
- 打開應用後選擇 **"學生"** 按鈕

#### 2️⃣ **輸入身份信息**
- 在文本框中輸入 **學號 + 姓名**（如：`12345678ＯＯＯ`）
- 信息會自動保存到本地存儲
- 下次使用時自動帶入

#### 3️⃣ **點名簽到**
- 點擊 **"廣播"** 按鈕
- 應用開始通過藍牙廣播身份信息
- 等待教師端掃描確認

#### 4️⃣ **查看位置**
- 點擊 **"查看熱力圖"** 按鈕
- 查看自己在教室的相對位置

---

## 🔐 安全機制

### 防欺騙驗證
應用採用多層安全機制：

1. **時間窗口驗證**（30 秒）
   - 只接受 30 秒內的簽到信息
   - 防止過期的重放攻擊

2. **滾動Hash**
   - 使用 SHA-1 Hash + 秘鑰驗證信息完整性
   - 每個時間周期生成不同的Hash

3. **XOR 加密**
   - 學號/姓名信息通過簡單 XOR 變換加密
   - 防止明文傳輸被截獲

**代碼位置**：`MainActivity.kt` - `generateRollingHash()` 和 `xorTransform()` 方法

---

## 📊 數據格式

### CSV 導出格式
```csv
學號/姓名,設備地址,最後更新時間
1091234 王小明,AA:BB:CC:DD:EE:FF,2024-03-01 14:30:45
1091235 李小華,11:22:33:44:55:66,2024-03-01 14:31:10
```

### 藍牙廣播信息結構
```
┌─────────────────────────────────────────┐
│ Hash部分 (6 字節)  │ 加密部分 (可變長度) │
│ (SHA-1 摘要)      │ (XOR 加密學號姓名) │
└─────────────────────────────────────────┘
Total Size: ≤ 24 字節
```

---

## 🐛 已知限制與待改進

### 當前限制
- ✅ BLE 掃描範圍受硬體限制（通常 30-100 米）
- ✅ RSSI 信號強度易受環境干擾，位置估算誤差較大
- ✅ 不支持多個教室同時使用（需要通過 SERVICE_UUID 區分）
- ✅ CSV 只支持本地文件，無雲同步

### 未來改進方向
- [ ] 支持自定義 SERVICE_UUID（多個教室）
- [ ] 優化卡爾曼濾波參數自適應
- [ ] 增加 UI 設計美化
- [ ] 支持 UI 多語言（目前中文）
- [ ] 添加更多詳細的位置估算算法
- [ ] 實現教師端數據上傳雲存儲
- [ ] 齁端前端整合功能

---

## 📋 權限說明

### 所需權限

| 權限 | 用途 | Android 版本 |
|------|------|-----------|
| `BLUETOOTH_SCAN` | 掃描藍牙設備 | API 31+ |
| `BLUETOOTH_ADVERTISE` | 廣播身份信息 | API 31+ |
| `BLUETOOTH_CONNECT` | 連接藍牙設備 | API 31+ |
| `BLUETOOTH` | 藍牙基本功能 | API 21-30 |
| `BLUETOOTH_ADMIN` | 藍牙管理功能 | API 21-30 |
| `ACCESS_FINE_LOCATION` | 精確位置（BLE 掃描需要）| API 21-30 |

> **注意**：應用會在首次運行時請求權限，所有功能都需要用戶同意才能使用

---

## 🧪 測試指南

### 單元測試
```bash
./gradlew testDebugUnitTest
```

### 集成測試（需真實設備）
```bash
./gradlew connectedAndroidTest
```

### 手動測試建議
1. **準備**：至少 2 台 Android 設備(約多越好)
2. **教師端設備**：打開應用 → 選擇"教師" → 打開掃描
3. **學生端設備**：打開應用 → 選擇"學生" → 輸入學號 → 點擊廣播
4. **驗證**：教師端應立即顯示簽到信息

---

## 📁 文件說明

### 配置文件
- **`build.gradle.kts`**（根）：項目級構建配置
- **`app/build.gradle.kts`**：應用級依賴和編譯選項
- **`gradle.properties`**：Gradle 全局屬性
- **`settings.gradle.kts`**：Gradle 設置和模塊配置

### 資源文件
- **`res/layout/`**：UI 布局文件（XML）
- **`res/values/`**：字符串、顏色、尺寸等資源

---

## 🤝 團隊協作指南

### 代碼風格
- 使用 **Kotlin 官方風格指南**
- 變量名使用駝峰命名法
- 類名使用帕斯卡命名法
- 添加有意義的代碼註釋（特別是複雜邏輯）

### 分支管理
```bash
# 創建功能分支
git checkout -b feature/your-feature-name

# 開發完成後提交 PR
# PR 說明要包含：
# 1. 修改內容簡述
# 2. 涉及的類和方法
# 3. 測試情況說明
```

### 常見開發任務

#### 添加新功能
1. 在 `MainActivity.kt` 中新增方法
2. 添加對應的 UI 控件到布局文件
3. 在 `setupListeners()` 中連接事件監聽
4. 測試並添加代碼註釋

#### 修改藍牙通信
1. 修改 `SERVICE_UUID` 以支持多個教室
2. 更新掃描回調 `scanCallback`
3. 測試信號掃描和廣播功能

#### 優化位置估算
1. 修改 `HeatmapActivity.kt` 中的距離計算
2. 調整 `KalmanFilter` 的參數
3. 在 `HeatmapView.kt` 更新繪製邏輯

---

## 📞 常見問題 (FAQ)

### Q1: 應用無法掃描到學生設備？
**A:**
- 檢查學生端是否已點擊"廣播"按鈕
- 確認教師端已打開掃描開關
- 檢查藍牙是否開啟且有足夠電量
- 試試重新啟動應用

### Q2: 導出的 CSV 文件在哪裡？
**A:**
- 文件自動保存到手機的 **"下載"** 文件夾
- 可通過文件管理器查看
- 使用 Excel 或 Google Sheets 打開

### Q3: 熱力圖的位置準不準？
**A:**
- 基於信號強度 (RSSI) 估算，會有誤差
- 環境金屬物體、牆壁等會影響信號
- 應用使用卡爾曼濾波已盡量平滑數據
- 實際場景中誤差通常在 5-10 米內

### Q4: 支持多個教室同時使用嗎？
**A:**
- 目前不支持，所有設備共用一個 SERVICE_UUID
- 如需支持，可在代碼中新增 SERVICE_UUID 列表

### Q5: 可以支持更多學生嗎？
**A:**
- 理論上支持無限學生（BLE 無連接）
- 實際受限於藍牙掃描範圍（30-100 米）

---

## 🔧 開發者信息

### 項目信息
- **Repository ID**: 1118093502
- **Language**: Kotlin 100%
- **主要構建工具**: Gradle with Kotlin DSL

### 聯繫方式
- GitHub: [@fygmuf5](https://github.com/fygmuf5)
- 項目地址: [https://github.com/fygmuf5/bluetooth](https://github.com/fygmuf5/bluetooth)
- Gmail: fygmuf5@gmail.com

---

## 📄 許可證

本項目為大學專題研究項目，請根據需要自由使用和修改。

---

**最後更新**: 2026-03-01  
**開發狀態**: 🚧 持續開發中

---

## 貢獻指南

歡迎組員的代碼貢獻！請：
1. Fork 本倉庫
2. 創建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 創建 Pull Request

感謝每位組員的貢獻！🙏
