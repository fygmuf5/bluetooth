package com.mcu.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.*
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
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
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class TeacherControlsFragment : Fragment() {

    private val SERVICE_UUID: UUID = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB")
    private val REFRESH_INTERVAL = 5 * 60 * 1000L

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner: BluetoothLeScanner? by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private lateinit var btnAttendanceToggle: Button
    private lateinit var exportCsvButton: Button
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
    private lateinit var gridAdapter: StudentGridAdapter

    private val attendanceResults = mutableMapOf<String, String>()
    private val attendanceRecords = mutableMapOf<String, Pair<String, String>>() 
    private var allStudentsList = mutableListOf<StudentStatus>() 
    private var filteredStudentsList = mutableListOf<StudentStatus>()
    
    private var currentXorKey: String? = null
    private var otpVerifyList: Map<String, String>? = null
    private var isScanning = false

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

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startSecureSessionLoop()
        else Toast.makeText(requireContext(), "請授權權限以開始點名", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_teacher_controls, container, false)
        
        btnAttendanceToggle = view.findViewById(R.id.btn_attendance_toggle)
        exportCsvButton = view.findViewById(R.id.export_csv_button)
        devicesListView = view.findViewById(R.id.devices_listview)
        tvTeacherStatus = view.findViewById(R.id.tv_teacher_status)
        tvAttendanceSummary = view.findViewById(R.id.tv_attendance_summary)
        btnSelectAll = view.findViewById(R.id.btn_select_all)
        etStudentSearch = view.findViewById(R.id.et_student_search)
        layoutStudentGridContainer = view.findViewById(R.id.layout_student_grid_container)
        
        teacherTabs = view.findViewById(R.id.teacher_tabs)
        layoutRealtimeList = view.findViewById(R.id.layout_realtime_list)
        rvStudentGrid = view.findViewById(R.id.rv_student_grid)
        
        receivedBroadcastsAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        devicesListView.adapter = receivedBroadcastsAdapter
        
        setupRecyclerView()
        setupListeners()
        return view
    }

    private fun setupRecyclerView() {
        gridAdapter = StudentGridAdapter(filteredStudentsList)
        rvStudentGrid.layoutManager = GridLayoutManager(context, 3)
        rvStudentGrid.adapter = gridAdapter
    }

    private fun setupListeners() {
        btnAttendanceToggle.setOnClickListener {
            if (!isScanning) {
                checkAndRequestPermissions()
            } else {
                stopAttendanceAndUpload()
            }
        }
        exportCsvButton.setOnClickListener { exportAttendanceToCsv() }

        btnSelectAll.setOnClickListener {
            val targetStatus = !allStudentsList.all { it.isPresent }
            allStudentsList.forEach { it.isPresent = targetStatus }
            gridAdapter.notifyDataSetChanged()
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
                    btnSelectAll.visibility = View.VISIBLE
                    filterStudents(etStudentSearch.text.toString())
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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
        gridAdapter.notifyDataSetChanged()
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

        val missing = required.filter { ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startSecureSessionLoop()
        else requestPermissionsLauncher.launch(required)
    }

    private fun startSecureSessionLoop() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(requireContext(), "請先開啟藍牙", Toast.LENGTH_SHORT).show()
            return
        }

        isScanning = true
        btnAttendanceToggle.text = "停止並回傳"
        btnAttendanceToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
        
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
                            updateGridFromOtpList(list)
                            tvTeacherStatus.text = "✅ 點名循環中 (${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())})"
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun updateGridFromOtpList(list: Map<String, String>?) {
        if (list == null) return
        val studentIds = list.keys.toList().sorted()
        
        if (allStudentsList.isEmpty()) {
            studentIds.forEach { id -> allStudentsList.add(StudentStatus(id)) }
            filterStudents(etStudentSearch.text.toString())
            updateAttendanceSummary()
        }
    }

    private fun stopAttendanceAndUpload() {
        isScanning = false
        handler.removeCallbacks(refreshRunnable)
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
        
        btnAttendanceToggle.text = "開始點名"
        btnAttendanceToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
        
        tvTeacherStatus.text = "正在回傳點名結果..."
        
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        allStudentsList.filter { it.isPresent }.forEach { student ->
            if (attendanceRecords.values.none { it.first == student.id }) {
                attendanceRecords["Manual_${student.id}"] = Pair(student.id, timeNow)
            }
        }

        if (attendanceRecords.isEmpty()) {
            tvTeacherStatus.text = "點名結束 (無紀錄)"
            return
        }

        var count = 0
        attendanceRecords.forEach { (address, pair) ->
            NetworkManager.syncAttendance(pair.first, address) {
                count++
                if (count == attendanceRecords.size) {
                    activity?.runOnUiThread {
                        tvTeacherStatus.text = "點名結束，已同步 $count 位學生"
                        Toast.makeText(requireContext(), "點名名單回傳完成", Toast.LENGTH_SHORT).show()
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
                val parts = decryptedStr.split("|")
                if (parts.size >= 2) {
                    val studentId = parts[0]
                    val receivedOtp = parts[1]
                    val expectedOtp = otpVerifyList?.get(studentId)
                    if (expectedOtp != null && receivedOtp == expectedOtp) {
                        processCheckInResult(studentId, address)
                    }
                }
            }
        }
    }

    private fun processCheckInResult(id: String, address: String) {
        val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val displayMsg = "[$id] ✅ 點名成功 ($timeString)"
        
        activity?.runOnUiThread {
            if (attendanceResults[address] != displayMsg) {
                attendanceResults[address] = displayMsg
                attendanceRecords[address] = Pair(id, timeString)
                updateListView()
                
                val indexInFull = allStudentsList.indexOfFirst { it.id == id }
                if (indexInFull != -1 && !allStudentsList[indexInFull].isPresent) {
                    allStudentsList[indexInFull].isPresent = true
                    val indexInFiltered = filteredStudentsList.indexOfFirst { it.id == id }
                    if (indexInFiltered != -1) {
                        gridAdapter.notifyItemChanged(indexInFiltered)
                    }
                    updateAttendanceSummary()
                }
            }
        }
    }

    private fun updateListView() {
        val displayList = attendanceResults.values.toList().reversed()
        receivedBroadcastsAdapter.clear()
        receivedBroadcastsAdapter.addAll(displayList)
        receivedBroadcastsAdapter.notifyDataSetChanged()
    }

    private fun exportAttendanceToCsv() {
        if (attendanceRecords.isEmpty() && allStudentsList.none { it.isPresent }) {
            Toast.makeText(requireContext(), "無點名資料可匯出", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "點名結果_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.csv"
        val csvContent = StringBuilder().append("學號,來源,時間\n")
        
        allStudentsList.filter { it.isPresent }.forEach { student ->
            val record = attendanceRecords.entries.find { it.value.first == student.id }
            val source = if (record != null) record.key else "手動勾選"
            val time = if (record != null) record.value.second else "N/A"
            csvContent.append("${student.id},$source,$time\n")
        }

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                requireContext().contentResolver.openOutputStream(it).use { os ->
                    os?.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    os?.write(csvContent.toString().toByteArray(Charset.forName("UTF-8")))
                }
                Toast.makeText(requireContext(), "CSV 已儲存至下載資料夾", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "匯出失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        try { bleScanner?.stopScan(scanCallback) } catch(e: Exception){}
    }

    inner class StudentGridAdapter(private val students: List<StudentStatus>) : RecyclerView.Adapter<StudentGridAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvId: TextView = v.findViewById(R.id.tv_grid_student_id)
            val cbStatus: CheckBox = v.findViewById(R.id.cb_attendance_status)
            val cardView: View = v.findViewById(R.id.student_card_view)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_student_grid, parent, false)
            return ViewHolder(v)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val student = students[position]
            holder.tvId.text = student.id
            
            holder.cbStatus.setOnCheckedChangeListener(null)
            holder.cbStatus.isChecked = student.isPresent
            
            updateItemVisual(holder, student.isPresent)
            
            val toggleAction = { isChecked: Boolean ->
                student.isPresent = isChecked
                updateItemVisual(holder, isChecked)
                updateAttendanceSummary()
            }

            holder.cbStatus.setOnCheckedChangeListener { _, isChecked -> toggleAction(isChecked) }
            holder.cardView.setOnClickListener {
                val nextState = !student.isPresent
                holder.cbStatus.isChecked = nextState
            }
        }
        
        private fun updateItemVisual(holder: ViewHolder, isPresent: Boolean) {
            if (isPresent) {
                holder.cardView.setBackgroundColor(Color.parseColor("#E8F5E9"))
                holder.tvId.textColor(Color.parseColor("#2E7D32"))
            } else {
                holder.cardView.setBackgroundColor(Color.WHITE)
                holder.tvId.textColor(Color.parseColor("#333333"))
            }
        }

        private fun TextView.textColor(color: Int) = setTextColor(color)

        override fun getItemCount() = students.size
    }
}
