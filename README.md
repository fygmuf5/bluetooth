# MCU Bluetooth Attendance System

這是一個基於 Android + BLE（Bluetooth Low Energy）的課堂點名系統，分為學生端與教師端兩種角色。學生端會在點名期間透過藍牙廣播加密後的簽到資料，教師端則會掃描並驗證資料，最後同步出席紀錄與座標資訊到後端。

## 專案簡介

本專案主要用於大學課堂場景，提供以下核心能力：

- 學生登入與帳號註冊
- 點名流程中的 OTP / XOR 金鑰驗證
- BLE 廣播與掃描
- 教師端出席統計與 CSV 匯出
- 教室定位熱圖與座標查詢
- 和後端 API 互動以完成登入、點名、座標同步

## 目前功能

### 學生端

- 登入系統
- 註冊帳號（含驗證碼流程）
- 判斷學生/教師帳號類型
- 自動取得點名 token
- 使用 BLE 廣播加密後的 `student_id|otp` 資料
- 支援手動立即簽到
- 支援自動重複點名（背景循環）
- 需要 Bluetooth 權限才能正常廣播

### 教師端

- 啟動點名工作階段
- 從後端取得當次點名的 XOR key 和 OTP 名單
- 掃描 BLE 廣播資料並解密驗證
- 檢查學生是否有效簽到
- 顯示即時簽到結果
- 支援搜尋、全選、逐筆勾選學生
- 結束點名後同步出席結果到後端
- 匯出 CSV 至下載資料夾

### 定位與熱圖

- 由教師端輪詢學生座標資料
- 顯示 8×10 公尺教室網格
- 顯示三個 Raspberry Pi 接收端位置
- 視覺化呈現學生位置
- 紀錄已定位與待掃描學生數量

## 專案架構

```text
bluetooth/
├── app/
│   ├── src/main/java/com/mcu/bluetooth/
│   │   ├── RoleSelectionActivity.kt      # 登入與角色入口
│   │   ├── RegisterActivity.kt            # 註冊與驗證碼流程
│   │   ├── MainActivity.kt                # 主畫面，依角色切換學生/教師
│   │   ├── TeacherControlsFragment.kt     # 教師點名控制、BLE 掃描、CSV 輸出
│   │   ├── HeatmapFragment.kt             # 定位資料輪詢與更新熱圖
│   │   ├── HeatmapView.kt                 # 教室熱圖繪製
│   │   ├── NetworkManager.kt              # 通訊層，管理所有後端 API
│   │   ├── KalmanFilter.kt                # 簡單 RSSI 濾波器（目前未接入主要流程）
│   │   └── ...
│   ├── src/main/res/
│   │   ├── layout/                        # XML 介面與元件布局
│   │   ├── values/                        # 顏色、字串、主題等資源
│   │   └── xml/                           # backup / data extraction 設定
│   └── src/main/AndroidManifest.xml       # 權限與 Activity 註冊
├── gradle/
│   └── libs.versions.toml                 # Gradle 依賴版本設定
├── build.gradle.kts                       # Root Gradle 設定
├── settings.gradle.kts                    # 專案模組設定
├── gradle.properties                      # Gradle 全域設定
├── gradlew / gradlew.bat                  # Gradle Wrapper
├── README.md
└── .gitignore
```

## 技術棧

- 語言：Kotlin
- 平台：Android
- 構建工具：Gradle Kotlin DSL
- UI：AndroidX、Material Components、ConstraintLayout
- 通訊：BLE API、HTTP POST / JSON
- 後端互動：`NetworkManager.kt` 透過 `HttpURLConnection` 呼叫 API

## 版本與環境需求

- Android Studio
- Android SDK 36
- JDK 11
- 最低支援版本：API 31（Android 12）
- 測試裝置：實體 Android 裝置或模擬器

## 權限說明

App 會在執行時要求以下權限：

- `INTERNET`
- `BLUETOOTH`
- `BLUETOOTH_ADMIN`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`
- `ACCESS_FINE_LOCATION`

這些權限主要用於：

- 藍牙掃描與廣播
- 進行點名驗證
- 讀取裝置資訊和定位所需資料

## 快速開始

### 1. Clone 專案

```bash
git clone https://github.com/fygmuf5/bluetooth.git
cd bluetooth
```

### 2. 建置專案

```bash
./gradlew assembleDebug
```

### 3. 安裝到裝置

```bash
./gradlew installDebug
```

### 4. 啟動 App

可直接使用 Android Studio 打開專案後點選 Run，或在命令列執行 App 安裝流程。

## 測試

```bash
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest
```

## 主要 API 依賴

目前 `NetworkManager.kt` 直接呼叫後端 API，包含：

- 登入：`/api/auth/login`
- 發送註冊驗證碼：`/api/send-code`
- 註冊：`/api/register`
- 開始點名：`/api/session/start`
- 取得 OTP 名單：`/api/session/otp-list`
- 取得學生 token：`/api/session/get-token`
- 回傳點名結果：`/api/check-in`
- 上傳座標：`/api/coords`
- 取得座標：`/api/coords/get`
- 清除座標：`/api/coords/clear`

注意：這些 API 需要後端服務是可用的，否則 App 無法正常進行登入與點名。

## 目前已知限制

- App 的功能高度依賴後端 API，後端不可用時無法正常登入、點名與定位。
- 定位熱圖是基於後端回傳座標，而不是純本地計算 RSSI。
- `查詢紀錄` 選單目前只顯示開發中的提示，尚未完整實作。
- `KalmanFilter` 已存在，但目前沒有在主要流程中接上真正的 RSSI 定位計算。
- 目前沒有正式的 `LICENSE` 檔案。
- 某些開發測試入口（例如測試用教師／學生快速進入）仍可能存在，需要在正式發布前確認是否移除。

## 目前程式流程

```text
RoleSelectionActivity
    ├── 登入
    ├── 註冊
    └── 自動進入 MainActivity

MainActivity
    ├── 學生端：取得 token → XOR 加密 → BLE 廣播
    └── 教師端：TeacherControlsFragment + HeatmapFragment

TeacherControlsFragment
    ├── 藍牙掃描
    ├── 解密/驗證 OTP
    ├── 出席統計
    ├── CSV 匯出
    └── 回傳結果到後端

HeatmapFragment
    └── 輪詢座標並顯示熱圖
```

## 專案用途

本專案適合在以下情境中使用：

- 大學課堂點名
- 需要低功耗藍牙簽到的場景
- 需要教師端即時統計與定位的教室環境

## 備註

- 本倉庫目前以 Android Kotlin 專案為主，核心邏輯集中在 `app/src/main/java/com/mcu/bluetooth/`。
- 若要正式部署，建議再補齊後端環境、正式登入流程、安全儲存與授權條款。

