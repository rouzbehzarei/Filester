package com.roozbehzarei.filester.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import com.aptabase.Aptabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.roozbehzarei.filester.BaseApplication
import com.roozbehzarei.filester.BuildConfig
import com.roozbehzarei.filester.HistoryListAdapter
import com.roozbehzarei.filester.R
import com.roozbehzarei.filester.databinding.FragmentMainBinding
import com.roozbehzarei.filester.ui.UpdateDialog.Companion.VER_URL_KEY
import com.roozbehzarei.filester.viewmodel.FilesterViewModel
import com.roozbehzarei.filester.viewmodel.FilesterViewModelFactory
import com.roozbehzarei.filester.viewmodel.KEY_FILE_URI
import kotlinx.coroutines.launch

class MainFragment : Fragment() {

    // Binding object instance with access to the views in the fragment_upload.xml layout
    private lateinit var binding: FragmentMainBinding

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private var ongoingUploadSnackbar: Snackbar? = null

    private val viewModel: FilesterViewModel by activityViewModels {
        FilesterViewModelFactory(
            (activity?.application as BaseApplication).database.fileDao(),
            requireActivity().application
        )
    }

    private val fileSelector =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                val fileName = DocumentFile.fromSingleUri(requireContext(), uri)?.name
                viewModel.startUploadWork(uri, fileName ?: "null")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
        viewModel.getAppVersion()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Inflate the layout XML file and return a binding object instance
        binding = FragmentMainBinding.inflate(inflater, container, false)

        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.app_bar_main, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                when (menuItem.itemId) {
                    R.id.action_status -> {
                        val intent = CustomTabsIntent.Builder().build()
                        try {
                            intent.launchUrl(requireActivity(), Uri.parse(STATUS_URL))
                        } catch (_: Exception) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.toast_app_not_found),
                                Toast.LENGTH_LONG
                            ).show()
                        }

                    }

                    R.id.action_settings -> findNavController().navigate(MainFragmentDirections.actionMainFragmentToSettingsFragment())
                    R.id.action_about -> findNavController().navigate(MainFragmentDirections.actionMainFragmentToAboutFragment())
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val adapter = HistoryListAdapter { file, action ->
            when (action) {
                0 -> {
                    val sendIntent: Intent = Intent().apply {
                        setAction(Intent.ACTION_SEND)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "${file.fileUrl}\n${getString(R.string.message_shared_with_filester)}"
                        )
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    startActivity(shareIntent)
                }

                1 -> {
                    val clipboard =
                        activity?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip: ClipData = ClipData.newPlainText("file url", file.fileUrl)
                    clipboard.setPrimaryClip(clip)
                    Snackbar.make(
                        binding.snackbarLayout,
                        getString(R.string.snackbar_clipboard),
                        Snackbar.LENGTH_LONG
                    ).show()
                }

                2 -> {
                    showDeleteDialog(file.fileName) {
                        viewModel.deleteFile(file)
                    }

                }
            }

        }
        binding.fileListView.adapter = adapter
        viewModel.files.observe(viewLifecycleOwner) {
            adapter.submitList(it)
        }
        viewModel.files.observe(viewLifecycleOwner) {
            if (it.isEmpty()) {
                binding.textNoUploads.visibility = View.VISIBLE
                binding.fileListView.visibility = View.GONE
            } else {
                binding.textNoUploads.visibility = View.GONE
                binding.fileListView.visibility = View.VISIBLE
            }
        }

        // Ask user to grant notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) checkNotificationPermission()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fab.setOnClickListener {
            fileSelector.launch("*/*")
        }
        viewModel.outputWorkInfo.observe(viewLifecycleOwner, workInfoObserver())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.isFileDeleted) {
                        Snackbar.make(
                            binding.snackbarLayout,
                            getString(R.string.snackbar_delete_successful),
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                    if (state.appConfig != null) {
                        if (state.appConfig.versionCode > BuildConfig.VERSION_CODE) showUpdateDialog(
                            state.appConfig.downloadUrl
                        )
                    }
                    viewModel.uiStateConsumed()
                }
            }
        }

    }

    private fun workInfoObserver(): Observer<List<WorkInfo>> {
        return Observer {
            // If there are no matching work info, do nothing
            if (it.isEmpty()) {
                return@Observer
            }
            val workInfo = it[0]
            val fileUrl = workInfo.outputData.getString(KEY_FILE_URI)
            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> Aptabase.instance.trackEvent("file_upload_succeeded")
                WorkInfo.State.FAILED -> Aptabase.instance.trackEvent("file_upload_failed")
                WorkInfo.State.CANCELLED -> Aptabase.instance.trackEvent("file_upload_cancelled")
                else -> {}
            }
            if (!workInfo.state.isFinished) {
                isUploadInProgress(true)
                ongoingUploadSnackbar = Snackbar.make(
                    binding.snackbarLayout,
                    getString(R.string.snackbar_uploading),
                    Snackbar.LENGTH_INDEFINITE
                )
                ongoingUploadSnackbar?.setAction(getString(R.string.button_cancel)) {
                    viewModel.cancelUploadWork()
                }?.show()
            } else if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                showUploadDialog(true, fileUrl)
                viewModel.clearWorkQueue()
                isUploadInProgress(false)
                ongoingUploadSnackbar?.dismiss()
            } else {
                showUploadDialog(false, null)
                viewModel.clearWorkQueue()
                isUploadInProgress(false)
                ongoingUploadSnackbar?.dismiss()
            }
        }
    }

    private fun showUploadDialog(isUploadSuccessful: Boolean, fileUrl: String?) {
        val context = requireContext()
        val alertDialog = MaterialAlertDialogBuilder(context)
        if (isUploadSuccessful) {
            alertDialog.setTitle(resources.getString(R.string.title_upload_success))
                .setMessage(getString(R.string.message_upload_success))
                .setPositiveButton(getString(R.string.share)) { _, _ ->
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "$fileUrl\n${getString(R.string.message_shared_with_filester)}"
                        )
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    startActivity(shareIntent)
                }.setNegativeButton(getString(R.string.dialog_button_copy)) { _, _ ->
                    val clipboard =
                        activity?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip: ClipData = ClipData.newPlainText("file url", fileUrl)
                    clipboard.setPrimaryClip(clip)
                    Snackbar.make(
                        binding.snackbarLayout,
                        getString(R.string.snackbar_clipboard),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }.setNeutralButton(getString(R.string.dialog_button_close)) { dialog, _ ->
                    dialog.dismiss()
                }
        } else {
            alertDialog.setTitle(resources.getString(R.string.title_upload_error))
                .setMessage(getString(R.string.message_upload_error))
                .setPositiveButton(resources.getString(R.string.dialog_button_close)) { dialog, _ ->
                    dialog.dismiss()
                }
        }.setCancelable(false).show()
    }

    private fun showDeleteDialog(fileName: String, onPositiveButtonClicked: () -> Unit) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
        with(dialog) {
            setTitle(getString(R.string.dialog_delete_title))
            setMessage(getString(R.string.dialog_delete_message, fileName))
            setPositiveButton(getString(R.string.button_delete)) { _, _ ->
                onPositiveButtonClicked()
            }
            setNegativeButton(getString(R.string.button_cancel)) { dialog, _ ->
                dialog.dismiss()
            }.show()
        }
    }

    private fun isUploadInProgress(state: Boolean) {
        if (state) {
            binding.fab.hide()
            binding.progressIndicator.visibility = View.VISIBLE
        } else {
            binding.fab.show()
            binding.progressIndicator.visibility = View.GONE
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkNotificationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED -> requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                // TODO: Explain why the app needs this permission
            }
        }
    }

    private fun showUpdateDialog(url: String) {
        val updateDialog = UpdateDialog()
        val args = Bundle()
        args.putString(VER_URL_KEY, url)
        updateDialog.arguments = args
        if (!updateDialog.isAdded) updateDialog.show(parentFragmentManager, UpdateDialog.TAG)
    }

}