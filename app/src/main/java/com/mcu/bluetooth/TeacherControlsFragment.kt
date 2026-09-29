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

    private lateinit var btnAttendanceToggle: Button
    private lateinit var devicesListView: ListView
    private lateinit var tvTeacherStatus: TextView
    private lateinit var tvAttendanceSummary: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var etStudentSearch: EditText
    private lateinit var layoutStudentGridContainer: View
    private lateinit var receivedBroadcastsAdapter: ArrayAdapter<String>
    
    private lateinit var teacherTabs: TabLayout
    private lateinit var layoutRealtimeList: View
    private lateinit var rvStudentGrid: RecyclerView
    
    // 課表專用元件
    private lateinit var btnImportCsv: Button
    private lateinit var btnBackToSchedule: Button
    private lateinit var tvScheduleTitle: TextView

    private val attendanceResults = mutableMapOf<String, String>()
    private val attendanceRecords = mutableMapOf<String, Pair<String, String>>() 
    
    // 狀態管理
    private var allStudentsList = mutableListOf<StudentStatus>() 
    private var filteredStudentsList = mutableListOf<StudentStatus>()
    private var currentScheduleList = mutableListOf<ScheduleItem>()
    
    private var isViewingStudents = false 
    private var currentXorKey: String? = null
    private var otpVerifyList: Map<String, String>? = null
    private var isScanning = false
    private var currentSessionId: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (isScanning) {
                performSessionRefresh()
                handler.postDelayed(this, REFRESH_INTERVAL)
            }
        }
    }

    data class StudentStatus(val id: String, var isPresent: Boolean = false)
    data class ScheduleItem(val period: String, val courseName: String, val location: String = "")

    private val pickCsvLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri -> parseScheduleCsv(uri) }
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startSecureSessionLoop()
        else Toast.makeText(requireContext(), "請授權權限以開始點名", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_controls, container, false)
        
        btnAttendanceToggle = view.findViewById(R.id.btn_attendance_toggle)
        devicesListView = view.findViewById(R.id.devices_listview)
        tvTeacherStatus = view.findViewById(R.id.tv_teacher_status)
        tvAttendanceSummary = view.findViewById(R.id.tv_attendance_summary)
        btnSelectAll = view.findViewById(R.id.btn_select_all)
        etStudentSearch = view.findViewById(R.id.et_student_search)
        layoutStudentGridContainer = view.findViewById(R.id.layout_student_grid_container)
        
        teacherTabs = view.findViewById(R.id.teacher_tabs)
        layoutRealtimeList = view.findViewById(R.id.layout_realtime_list)
        rvStudentGrid = view.findViewById(R.id.rv_student_grid)
        
        btnImportCsv = view.findViewById(R.id.btn_import_csv)
        btnBackToSchedule = view.findViewById(R.id.btn_back_to_schedule)
        tvScheduleTitle = view.findViewById(R.id.tv_schedule_title)
        
        receivedBroadcastsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter
        
        loadDefaultSchedule()
        showScheduleLayout()
        setupListeners()
        return view
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

    private fun showScheduleLayout() {
        isViewingStudents = false
        btnBackToSchedule.visibility = View.GONE
        etStudentSearch.visibility = View.GONE
        btnSelectAll.visibility = View.GONE
        tvScheduleTitle.text = "學期課表"
        
        rvStudentGrid.layoutManager = GridLayoutManager(context, 2)
        rvStudentGrid.adapter = ScheduleListAdapter(currentScheduleList) { item ->
            enterCourseStudents(item)
        }
    }

    private fun enterCourseStudents(item: ScheduleItem) {
        isViewingStudents = true
        btnBackToSchedule.visibility = View.VISIBLE
        etStudentSearch.visibility = View.VISIBLE
        btnSelectAll.visibility = View.VISIBLE
        tvScheduleTitle.text = "課程學生：${item.courseName} (${item.location})"
        
        loadDefaultStudentsForCourse()
        
        rvStudentGrid.layoutManager = GridLayoutManager(context, 3)
        rvStudentGrid.adapter = StudentGridAdapter(filteredStudentsList)
        updateAttendanceSummary()
    }

    private fun loadDefaultStudentsForCourse() {
        allStudentsList.clear()
        val baseList = otpVerifyList?.keys?.toList() ?: listOf("11012345", "11012346", "11012347", "11012348", "11012349")
        baseList.forEach { id ->
            val isChecked = attendanceRecords.values.any { it.first == id }
            allStudentsList.add(StudentStatus(id, isChecked))
        }
        filterStudents(etStudentSearch.text.toString())
    }

    private fun setupListeners() {
        btnAttendanceToggle.setOnClickListener {
            if (!isScanning) checkAndRequestPermissions()
            else stopAttendanceAndUpload()
        }

        btnImportCsv.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "text/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pickCsvLauncher.launch(Intent.createChooser(intent, "選擇課表 CSV"))
        }

        btnBackToSchedule.setOnClickListener { showScheduleLayout() }

        btnSelectAll.setOnClickListener {
            val targetStatus = !allStudentsList.all { it.isPresent }
            allStudentsList.forEach { it.isPresent = targetStatus }
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
                if (tab?.position == 0) {
                    layoutRealtimeList.visibility = View.VISIBLE
                    layoutStudentGridContainer.visibility = View.GONE
                    btnSelectAll.visibility = View.GONE
                } else {
                    layoutRealtimeList.visibility = View.GONE
                    layoutStudentGridContainer.visibility = View.VISIBLE
                    if (isViewingStudents) {
                        btnSelectAll.visibility = View.VISIBLE
                        etStudentSearch.visibility = View.VISIBLE
                        btnBackToSchedule.visibility = View.VISIBLE
                    } else {
                        showScheduleLayout()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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
                    Toast.makeText(requireContext(), "成功匯入 ${currentScheduleList.size} 節課程！", Toast.LENGTH_SHORT).show()
                    if (!isViewingStudents) showScheduleLayout()
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
        if (isViewingStudents) rvStudentGrid.adapter?.notifyDataSetChanged()
    }

    private fun updateAttendanceSummary() {
        val total = allStudentsList.size
        val present = allStudentsList.count { it.isPresent }
        val percent = if (total > 0) (present * 100 / total) else 0
        tvAttendanceSummary.text = "出席：$present / $total ($percent%)"
    }

    private fun checkAndRequestPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        
        val missing = required.filter { 
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isEmpty()) {
            startSecureSessionLoop()
        } else {
            // 正確修復：針對 List 呼叫 toTypedArray() 轉為 Array 給 launch 使用
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
        allStudentsList.forEach { it.isPresent = false }
        filterStudents(etStudentSearch.text.toString())
        updateListView()
        updateAttendanceSummary()
        performSessionRefresh()
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL)
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        bleScanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
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
                            if (isViewingStudents) loadDefaultStudentsForCourse()
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
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
        btnAttendanceToggle.text = "開始點名"
        btnAttendanceToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        tvTeacherStatus.text = "回傳結果並清除定位緩存..."
        val email = activity?.intent?.getStringExtra("EXTRA_EMAIL") ?: ""
        NetworkManager.clearCoordinates(email, "teacherPassword", currentSessionId) { _, _ -> }
        
        val totalToUpload = attendanceRecords.size
        if (totalToUpload == 0) {
            tvTeacherStatus.text = "點名結束 (無紀錄)"
            return
        }
        val completedCount = AtomicInteger(0)
        attendanceRecords.forEach { (address, pair) ->
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
            if (decryptedStr.contains("|")) {
                val studentId = decryptedStr.split("|")[0]
                val receivedOtp = decryptedStr.split("|")[1]
                if (otpVerifyList?.get(studentId) == receivedOtp) processCheckInResult(studentId, address)
            }
        }
    }

    private fun processCheckInResult(id: String, address: String) {
        val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        activity?.runOnUiThread {
            if (attendanceResults[address] != id) {
                attendanceResults[address] = id
                attendanceRecords[address] = Pair(id, timeString)
                updateListView()
                
                allStudentsList.find { it.id == id }?.let { student ->
                    if (!student.isPresent) {
                        student.isPresent = true
                        // 確保只有在檢視學生列表時才更新 RecyclerView
                        if (isViewingStudents) {
                            val adapter = rvStudentGrid.adapter
                            if (adapter is StudentGridAdapter) {
                                val indexInFiltered = filteredStudentsList.indexOfFirst { it.id == id }
                                if (indexInFiltered != -1) {
                                    adapter.notifyItemChanged(indexInFiltered)
                                }
                            }
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

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
    }

    inner class ScheduleListAdapter(
        private val items: List<ScheduleItem>,
        private val onItemClick: (ScheduleItem) -> Unit
    ) : RecyclerView.Adapter<ScheduleListAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val btnCourse: Button = Button(v.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(12, 12, 12, 12) }
                textSize = 18f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E91E63")) 
                setPadding(24, 50, 24, 50)
                isAllCaps = false
            }
            init { (v as ViewGroup).addView(btnCourse) }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ll = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            return ViewHolder(ll)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.btnCourse.text = item.courseName
            holder.btnCourse.setOnClickListener { onItemClick(item) }
        }
        override fun getItemCount() = items.size
    }

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
