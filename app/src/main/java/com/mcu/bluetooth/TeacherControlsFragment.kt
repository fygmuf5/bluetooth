package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class TeacherControlsFragment : Fragment() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    private val REFRESH_INTERVAL = 5 * 60 * 1000L

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }

    // --- 畫面一：選擇課程表格 (登入後首頁) ---
    private lateinit var layoutCourseSelection: View
    private lateinit var rvCourseSchedule: RecyclerView
    private lateinit var btnImportCsv: Button
    private lateinit var tvScheduleTitle: TextView

    // --- 畫面二：課程點名與管理功能介面 ---
    private lateinit var layoutCourseDetail: View
    private lateinit var btnBackToSchedule: Button
    private lateinit var tvSelectedCourseTitle: TextView
    private lateinit var tvTeacherStatus: TextView
    private lateinit var btnAttendanceToggle: Button
    private lateinit var tvAttendanceSummary: TextView
    private lateinit var teacherTabs: TabLayout

    // Tab 1: 即時列表
    private lateinit var layoutRealtimeList: View
    private lateinit var devicesListView: ListView
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>

    // Tab 2: 學生名單
    private lateinit var layoutStudentGridContainer: View
    private lateinit var etStudentSearch: EditText
    private lateinit var btnSelectAll: Button
    private lateinit var rvStudentGrid: RecyclerView

    // Tab 3: 教室熱力圖
    private lateinit var layoutHeatmapContainer: View
    private lateinit var tvHeatmapStatus: TextView
    private lateinit var tvHeatmapStudentCount: TextView
    private lateinit var heatmapView: HeatmapView

    // --- 資料結構 ---
    data class ScheduleItem(val period: String, val courseName: String, val location: String = "")
    data class StudentStatus(val id: String, var isPresent: Boolean = false)

    private val currentScheduleList = mutableListOf<ScheduleItem>()
    private var selectedCourse: ScheduleItem? = null

    // 各課程紀錄隔離：課程名稱 -> (設備地址 -> Pair(學號, 時間))
    private val courseRecordsMap = mutableMapOf<String, MutableMap<String, Pair<String, String>>>()
    private val attendanceResults = mutableMapOf<String, String>()
    private val attendanceRecords = mutableMapOf<String, Pair<String, String>>()

    private val allStudentsList = mutableListOf<StudentStatus>()
    private val filteredStudentsList = mutableListOf<StudentStatus>()

    private var currentXorKey: String? = null
    private var otpVerifyList: Map<String, String>? = null
    private var isScanning = false
    private var currentSessionId: String = ""

    // 熱力圖輪詢
    private val studentLocations = mutableMapOf<String, PointF>()
    private var fullStudentRoster = setOf<String>()
    private var isHeatmapLoopRunning = false
    private val heatmapHandler = Handler(Looper.getMainLooper())
    private val heatmapRunnable = object : Runnable {
        override fun run() {
            if (isHeatmapLoopRunning && isAdded) {
                fetchLocationsFromServer()
                heatmapHandler.postDelayed(this, 2000)
            }
        }
    }

    // 點名週期輪詢
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
                performSessionRefresh()
                handler.postDelayed(this, REFRESH_INTERVAL)
            }
        }
    }

    private val pickCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri -> parseScheduleCsv(uri) }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.all { it }) startSecureSessionLoop()
        else Toast.makeText(requireContext(), "請授權藍牙與定位權限以開始點名", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_controls, container, false)
        initializeViews(view)
        loadDefaultSchedule()
        setupListeners()
        showCourseSelectionScreen()
        return view
    }

    private fun initializeViews(view: View) {
        // 畫面一
        layoutCourseSelection = view.findViewById(R.id.layout_course_selection)
        rvCourseSchedule = view.findViewById(R.id.rv_course_schedule)
        btnImportCsv = view.findViewById(R.id.btn_import_csv)
        tvScheduleTitle = view.findViewById(R.id.tv_schedule_title)

        // 畫面二
        layoutCourseDetail = view.findViewById(R.id.layout_course_detail)
        btnBackToSchedule = view.findViewById(R.id.btn_back_to_schedule)
        tvSelectedCourseTitle = view.findViewById(R.id.tv_selected_course_title)
        tvTeacherStatus = view.findViewById(R.id.tv_teacher_status)
        btnAttendanceToggle = view.findViewById(R.id.btn_attendance_toggle)
        tvAttendanceSummary = view.findViewById(R.id.tv_attendance_summary)
        teacherTabs = view.findViewById(R.id.teacher_tabs)

        // Tab 1
        layoutRealtimeList = view.findViewById(R.id.layout_realtime_list)
        devicesListView = view.findViewById(R.id.devices_listview)
        receivedBroadcastsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter

        // Tab 2
        layoutStudentGridContainer = view.findViewById(R.id.layout_student_grid_container)
        etStudentSearch = view.findViewById(R.id.et_student_search)
        btnSelectAll = view.findViewById(R.id.btn_select_all)
        rvStudentGrid = view.findViewById(R.id.rv_student_grid)

        // Tab 3
        layoutHeatmapContainer = view.findViewById(R.id.layout_heatmap_container)
        tvHeatmapStatus = view.findViewById(R.id.tv_heatmap_status)
        tvHeatmapStudentCount = view.findViewById(R.id.tv_heatmap_student_count)
        heatmapView = view.findViewById(R.id.heatmap_view)
    }

    private fun loadDefaultSchedule() {
        if (currentScheduleList.isNotEmpty()) return
        currentScheduleList.add(ScheduleItem("週二 05-07", "人工智慧", "F609"))
        currentScheduleList.add(ScheduleItem("週三 02-04", "計算機概論", "S210"))
        currentScheduleList.add(ScheduleItem("週四 02-04", "模型 (英)", "S401"))
        currentScheduleList.add(ScheduleItem("週四 20", "班會", "S401"))
        currentScheduleList.add(ScheduleItem("週四 05-07", "計算機概論", "EE502"))
        currentScheduleList.add(ScheduleItem("週五 06-08", "電資探索", "S105"))
    }

    /**
     * 畫面一：僅顯示選擇課程表格 (登入後首頁)
     */
    private fun showCourseSelectionScreen() {
        stopHeatmapSyncLoop()
        selectedCourse = null

        layoutCourseSelection.visibility = View.VISIBLE
        layoutCourseDetail.visibility = View.GONE

        rvCourseSchedule.layoutManager = GridLayoutManager(requireContext(), 2)
        rvCourseSchedule.adapter = CourseScheduleAdapter(currentScheduleList) { course ->
            enterCourse(course)
        }
    }

    /**
     * 畫面二：點選特定課程後進入功能介面
     */
    private fun enterCourse(course: ScheduleItem) {
        selectedCourse = course

        layoutCourseSelection.visibility = View.GONE
        layoutCourseDetail.visibility = View.VISIBLE

        tvSelectedCourseTitle.text = "${course.courseName} (${course.location}) - ${course.period}"

        loadStudentsForCourse(course.courseName)

        rvStudentGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        rvStudentGrid.adapter = StudentGridAdapter(filteredStudentsList)

        // 重置為預設第 1 個 Tab (即時列表)
        teacherTabs.getTabAt(0)?.select()
        layoutRealtimeList.visibility = View.VISIBLE
        layoutStudentGridContainer.visibility = View.GONE
        layoutHeatmapContainer.visibility = View.GONE

        updateAttendanceSummary()
        updateListView()

        if (isScanning) {
            btnAttendanceToggle.text = "停止並回傳"
            btnAttendanceToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
        } else {
            btnAttendanceToggle.text = "開始點名"
            btnAttendanceToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        }
    }

    private fun loadStudentsForCourse(courseName: String) {
        allStudentsList.clear()
        val baseList = otpVerifyList?.keys?.toList() ?: listOf("11012345", "11012346", "11012347", "11012348", "11012349")
        val currentCourseRecords = courseRecordsMap[courseName] ?: emptyMap()

        baseList.forEach { id ->
            val isChecked = currentCourseRecords.values.any { it.first == id }
            allStudentsList.add(StudentStatus(id, isChecked))
        }
        filterStudents(etStudentSearch.text.toString())
    }

    private fun setupListeners() {
        btnImportCsv.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "text/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pickCsvLauncher.launch(Intent.createChooser(intent, "選擇課表 CSV"))
        }

        btnBackToSchedule.setOnClickListener {
            if (isScanning) {
                AlertDialog.Builder(requireContext())
                    .setTitle("點名進行中")
                    .setMessage("目前點名正在進行中，返回課表將會停止點名並同步結果，是否確定返回？")
                    .setPositiveButton("停止並返回") { _, _ ->
                        stopAttendanceAndUpload()
                        showCourseSelectionScreen()
                    }
                    .setNegativeButton("繼續點名", null)
                    .show()
            } else {
                showCourseSelectionScreen()
            }
        }

        btnAttendanceToggle.setOnClickListener {
            if (!isScanning) checkAndRequestPermissions()
            else stopAttendanceAndUpload()
        }

        btnSelectAll.setOnClickListener {
            val targetStatus = !allStudentsList.all { it.isPresent }
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            allStudentsList.forEach { student ->
                student.isPresent = targetStatus
                selectedCourse?.courseName?.let { course ->
                    val map = courseRecordsMap.getOrPut(course) { mutableMapOf() }
                    val manualKey = "Manual_${student.id}"
                    if (targetStatus) map[manualKey] = Pair(student.id, timeNow)
                    else map.remove(manualKey)
                }
            }
            rvStudentGrid.adapter?.notifyDataSetChanged()
            updateAttendanceSummary()
        }

        etStudentSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterStudents(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        teacherTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        layoutRealtimeList.visibility = View.VISIBLE
                        layoutStudentGridContainer.visibility = View.GONE
                        layoutHeatmapContainer.visibility = View.GONE
                        stopHeatmapSyncLoop()
                    }
                    1 -> {
                        layoutRealtimeList.visibility = View.GONE
                        layoutStudentGridContainer.visibility = View.VISIBLE
                        layoutHeatmapContainer.visibility = View.GONE
                        stopHeatmapSyncLoop()
                        filterStudents(etStudentSearch.text.toString())
                    }
                    2 -> {
                        layoutRealtimeList.visibility = View.GONE
                        layoutStudentGridContainer.visibility = View.GONE
                        layoutHeatmapContainer.visibility = View.VISIBLE
                        startHeatmapSyncLoop()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // --- 熱力圖相關邏輯 ---
    private fun startHeatmapSyncLoop() {
        if (isHeatmapLoopRunning) return
        isHeatmapLoopRunning = true

        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: "teacher@example.com"
        NetworkManager.getVerifyList(email) { list ->
            if (list != null) {
                fullStudentRoster = list.keys
            }
            heatmapHandler.post(heatmapRunnable)
        }
    }

    private fun stopHeatmapSyncLoop() {
        isHeatmapLoopRunning = false
        heatmapHandler.removeCallbacks(heatmapRunnable)
    }

    private fun fetchLocationsFromServer() {
        if (!isAdded) return

        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: "teacher@example.com"
        val password = requireContext().getSharedPreferences("AttendanceApp", Context.MODE_PRIVATE)
            .getString("saved_password", "teacherPassword") ?: "teacherPassword"
        val sessionId = if (currentSessionId.isNotEmpty()) currentSessionId 
                        else "sess_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

        NetworkManager.getStudentCoordinates(email, password, sessionId) { response ->
            if (response != null && isAdded) {
                val coordsObj = response.optJSONObject("coords")
                studentLocations.clear()

                val receivedIds = mutableSetOf<String>()
                if (coordsObj != null) {
                    val keys = coordsObj.keys()
                    while (keys.hasNext()) {
                        val studentId = keys.next()
                        val coordData = coordsObj.optJSONObject(studentId)
                        if (coordData != null) {
                            val x = coordData.optDouble("x", 0.0).toFloat()
                            val y = coordData.optDouble("y", 0.0).toFloat()
                            studentLocations[studentId] = PointF(x, y)
                            receivedIds.add(studentId)
                        }
                    }
                }

                // 防呆：在名單但無座標者置於 (0, 0)
                fullStudentRoster.forEach { id ->
                    if (!receivedIds.contains(id)) {
                        studentLocations[id] = PointF(0f, 0f)
                    }
                }

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    heatmapView.updateStudentLocations(studentLocations)
                    val updatedAt = response.optString("updated_at", "未知")
                    val activeCount = receivedIds.size
                    val missingCount = fullStudentRoster.size - activeCount
                    tvHeatmapStudentCount.text = "即時定位 - 已定位: $activeCount, 待掃描(左上角): $missingCount (更新: $updatedAt)"
                }
            } else {
                Log.e("TeacherControlsFragment", "熱力圖座標抓取失敗")
            }
        }
    }

    private fun parseScheduleCsv(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charset.forName("UTF-8"))).use { reader ->
                    currentScheduleList.clear()
                    reader.readLine()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val tokens = line!!.split(",")
                        if (tokens.size >= 2) {
                            val period = tokens[0].trim().replace("\"", "")
                            val course = tokens[1].trim().replace("\"", "")
                            val loc = if (tokens.size >= 3) tokens[2].trim().replace("\"", "") else ""
                            if (period.isNotEmpty() && course.isNotEmpty()) {
                                currentScheduleList.add(ScheduleItem(period, course, loc))
                            }
                        }
                    }
                    Toast.makeText(requireContext(), "課表匯入成功", Toast.LENGTH_SHORT).show()
                    if (selectedCourse == null) showCourseSelectionScreen()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "解析失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun filterStudents(query: String) {
        filteredStudentsList.clear()
        if (query.isEmpty()) {
            filteredStudentsList.addAll(allStudentsList)
        } else {
            val lowerCaseQuery = query.lowercase()
            allStudentsList.filter { it.id.lowercase().contains(lowerCaseQuery) }
                .forEach { filteredStudentsList.add(it) }
        }
        val adapter = rvStudentGrid.adapter
        if (adapter is StudentGridAdapter) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateAttendanceSummary() {
        val total = allStudentsList.size
        val present = allStudentsList.count { it.isPresent }
        val percent = if (total > 0) (present * 100 / total) else 0
        tvAttendanceSummary.text = "出席：$present / $total ($percent%)"
    }

    private fun checkAndRequestPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startSecureSessionLoop()
        } else {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startSecureSessionLoop() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(requireContext(), "請先開啟藍牙", Toast.LENGTH_SHORT).show()
            return
        }
        isScanning = true
        btnAttendanceToggle.text = "停止並回傳"
        btnAttendanceToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
        currentSessionId = "sess_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())

        attendanceResults.clear()
        attendanceRecords.clear()
        selectedCourse?.courseName?.let { courseRecordsMap[it]?.clear() }

        allStudentsList.forEach { it.isPresent = false }
        filterStudents(etStudentSearch.text.toString())
        updateListView()
        updateAttendanceSummary()
        performSessionRefresh()
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL)

        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun performSessionRefresh() {
        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: ""
        tvTeacherStatus.text = "正在同步伺服器點名訊號..."
        NetworkManager.startAttendanceSession(email) { xorKey ->
            activity?.runOnUiThread {
                if (xorKey != null) {
                    currentXorKey = xorKey
                    NetworkManager.getVerifyList(email) { list ->
                        activity?.runOnUiThread {
                            otpVerifyList = list
                            selectedCourse?.courseName?.let { loadStudentsForCourse(it) }
                            tvTeacherStatus.text = "✅ 點名循環中 (${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())})"
                        }
                    }
                }
            }
        }
    }

    private fun stopAttendanceAndUpload() {
        isScanning = false
        handler.removeCallbacks(refreshRunnable)
        try { bleScanner?.stopScan(scanCallback) } catch (e: Exception) {}
        btnAttendanceToggle.text = "開始點名"
        btnAttendanceToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        tvTeacherStatus.text = "正在回傳點名結果..."

        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val courseName = selectedCourse?.courseName
        val uploadMap = if (courseName != null) {
            courseRecordsMap.getOrPut(courseName) { mutableMapOf() }
        } else {
            attendanceRecords
        }

        allStudentsList.filter { it.isPresent }.forEach { student ->
            if (uploadMap.values.none { it.first == student.id }) {
                uploadMap["Manual_${student.id}"] = Pair(student.id, timeNow)
            }
        }

        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: ""
        val password = requireContext().getSharedPreferences("AttendanceApp", Context.MODE_PRIVATE)
            .getString("saved_password", "teacherPassword") ?: "teacherPassword"
        NetworkManager.clearCoordinates(email, password, currentSessionId) { _, _ -> }

        if (uploadMap.isEmpty()) {
            tvTeacherStatus.text = "點名結束 (無紀錄)"
            return
        }

        val completedCount = AtomicInteger(0)
        val totalToUpload = uploadMap.size
        uploadMap.forEach { (address, pair) ->
            NetworkManager.syncAttendance(pair.first, address) {
                if (completedCount.incrementAndGet() == totalToUpload) {
                    activity?.runOnUiThread {
                        tvTeacherStatus.text = "點名結束，已同步 $totalToUpload 位學生"
                        Toast.makeText(requireContext(), "回傳完成", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            val address = result.device.address
            val xorKey = currentXorKey ?: return

            val keyBytes = xorKey.toByteArray(Charset.forName("UTF-8"))
            val decryptedBytes = ByteArray(payload.size)
            for (i in payload.indices) {
                decryptedBytes[i] = (payload[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            val decryptedStr = String(decryptedBytes, Charset.forName("UTF-8"))

            val parts = decryptedStr.split("|")
            if (parts.size >= 2) {
                val studentId = parts[0]
                val receivedOtp = parts[1]
                if (otpVerifyList?.get(studentId) == receivedOtp) processCheckInResult(studentId, address)
            }
        }
    }

    private fun processCheckInResult(id: String, address: String) {
        val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.runOnUiThread {
            val courseName = selectedCourse?.courseName
            val currentCourseRecords = courseName?.let { courseRecordsMap.getOrPut(it) { mutableMapOf() } }
            val alreadyInCourse = currentCourseRecords?.containsKey(address) == true

            if (attendanceResults[address] != id || (courseName != null && !alreadyInCourse)) {
                attendanceResults[address] = id
                attendanceRecords[address] = Pair(id, timeString)
                currentCourseRecords?.put(address, Pair(id, timeString))

                updateListView()

                allStudentsList.find { it.id == id }?.let { student ->
                    if (!student.isPresent) {
                        student.isPresent = true
                        val adapter = rvStudentGrid.adapter
                        if (adapter is StudentGridAdapter) {
                            val index = filteredStudentsList.indexOf(student)
                            if (index != -1) adapter.notifyItemChanged(index)
                        }
                        updateAttendanceSummary()
                    }
                }
            }
        }
    }

    private fun updateListView() {
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(attendanceResults.values.toList().map { "[$it] ✅ 點名成功" }.reversed())
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
        stopHeatmapSyncLoop()
        try { bleScanner?.stopScan(scanCallback) } catch (e: Exception) {}
    }

    // --- 課表清單適配器 (畫面一使用) ---
    inner class CourseScheduleAdapter(
        private val items: List<ScheduleItem>,
        private val onItemClick: (ScheduleItem) -> Unit
    ) : RecyclerView.Adapter<CourseScheduleAdapter.ViewHolder>() {

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvPeriod: TextView = v.findViewById(R.id.tv_course_period)
            val tvCourseName: TextView = v.findViewById(R.id.tv_course_name)
            val tvLocation: TextView = v.findViewById(R.id.tv_course_location)
            val cardView: View = v.findViewById(R.id.card_course_item)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_course_schedule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvPeriod.text = item.period
            holder.tvCourseName.text = item.courseName
            holder.tvLocation.text = if (item.location.isNotEmpty()) "教室: ${item.location}" else "未指定教室"
            holder.cardView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }

    // --- 學生點名格狀適配器 (畫面二使用) ---
    inner class StudentGridAdapter(private val students: List<StudentStatus>) : RecyclerView.Adapter<StudentGridAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvId: TextView = v.findViewById(R.id.tv_grid_student_id)
            val cbStatus: CheckBox = v.findViewById(R.id.cb_attendance_status)
            val cardView: View = v.findViewById(R.id.student_card_view)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_student_grid, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val student = students[position]
            holder.tvId.text = student.id
            holder.cbStatus.setOnCheckedChangeListener(null)
            holder.cbStatus.isChecked = student.isPresent
            updateItemVisual(holder, student.isPresent)

            holder.cbStatus.setOnCheckedChangeListener { _, isChecked ->
                student.isPresent = isChecked
                selectedCourse?.courseName?.let { course ->
                    val map = courseRecordsMap.getOrPut(course) { mutableMapOf() }
                    val manualKey = "Manual_${student.id}"
                    if (isChecked) map[manualKey] = Pair(student.id, "Manual")
                    else map.remove(manualKey)
                }
                updateItemVisual(holder, isChecked)
                updateAttendanceSummary()
            }
            holder.cardView.setOnClickListener { holder.cbStatus.isChecked = !student.isPresent }
        }

        private fun updateItemVisual(holder: ViewHolder, isPresent: Boolean) {
            if (isPresent) {
                holder.cardView.setBackgroundColor(Color.parseColor("#E8F5E9"))
                holder.tvId.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                holder.cardView.setBackgroundColor(Color.WHITE)
                holder.tvId.setTextColor(Color.parseColor("#333333"))
            }
        }

        override fun getItemCount() = students.size
    }
}
