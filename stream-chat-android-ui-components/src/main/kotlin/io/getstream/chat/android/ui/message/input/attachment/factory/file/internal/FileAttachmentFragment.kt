/*
 * Copyright (c) 2014-2022 Stream.io Inc. All rights reserved.
 *
 * Licensed under the Stream License;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://github.com/GetStream/stream-chat-android/blob/main/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getstream.chat.android.ui.message.input.attachment.factory.file.internal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.getstream.sdk.chat.SelectFilesContract
import com.getstream.sdk.chat.model.AttachmentMetaData
import com.getstream.sdk.chat.utils.AttachmentFilter
import com.getstream.sdk.chat.utils.PermissionChecker
import com.getstream.sdk.chat.utils.StorageHelper
import io.getstream.chat.android.core.internal.coroutines.DispatcherProvider
import io.getstream.chat.android.ui.R
import io.getstream.chat.android.ui.common.extensions.internal.streamThemeInflater
import io.getstream.chat.android.ui.databinding.StreamUiFragmentAttachmentFileBinding
import io.getstream.chat.android.ui.message.input.MessageInputViewStyle
import io.getstream.chat.android.ui.message.input.attachment.AttachmentSource
import io.getstream.chat.android.ui.message.input.attachment.factory.AttachmentsPickerTabListener
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Represents the tab of the attachment picker with a list of files.
 */
internal class FileAttachmentFragment : Fragment() {

    private var _binding: StreamUiFragmentAttachmentFileBinding? = null
    private val binding get() = _binding!!

    private val storageHelper: StorageHelper = StorageHelper()
    private val permissionChecker: PermissionChecker = PermissionChecker()
    private val attachmentFilter: AttachmentFilter = AttachmentFilter()
    private var activityResultLauncher: ActivityResultLauncher<Unit>? = null

    /**
     * Style for the attachment picker dialog.
     */
    private lateinit var style: MessageInputViewStyle

    /**
     * A listener invoked when attachments are selected in the attachment tab.
     */
    private var attachmentsPickerTabListener: AttachmentsPickerTabListener? = null

    /**
     * Initializes the dialog with the style.
     *
     * @param style Style for the dialog.
     */
    fun setStyle(style: MessageInputViewStyle) {
        this.style = style
    }

    private val fileAttachmentsAdapter: FileAttachmentAdapter by lazy {
        FileAttachmentAdapter(style) {
            updateFileAttachment(it)
        }
    }

    private var selectedAttachments: Set<AttachmentMetaData> = emptySet()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = StreamUiFragmentAttachmentFileBinding.inflate(requireContext().streamThemeInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (::style.isInitialized) {
            setupViews()
            setupResultListener()
            checkPermissions()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activityResultLauncher?.unregister()
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        attachmentsPickerTabListener = null
    }

    private fun updateFileAttachment(attachmentMetaData: AttachmentMetaData) {
        if (attachmentMetaData.isSelected) {
            attachmentMetaData.isSelected = false
            selectedAttachments = selectedAttachments - attachmentMetaData
            fileAttachmentsAdapter.deselectAttachment(attachmentMetaData)
        } else {
            attachmentMetaData.isSelected = true
            selectedAttachments = selectedAttachments + attachmentMetaData
            fileAttachmentsAdapter.selectAttachment(attachmentMetaData)
        }
        attachmentsPickerTabListener?.onSelectedAttachmentsChanged(selectedAttachments.toList(), AttachmentSource.FILE)
    }

    private fun setupViews() {
        binding.apply {
            val style = style.attachmentSelectionDialogStyle
            grantPermissionsInclude.grantPermissionsImageView.setImageDrawable(style.allowAccessToFilesIcon)
            grantPermissionsInclude.grantPermissionsTextView.text = style.allowAccessToFilesText
            style.grantPermissionsTextStyle.apply(grantPermissionsInclude.grantPermissionsTextView)
            grantPermissionsInclude.grantPermissionsTextView.setOnClickListener {
                checkPermissions()
            }
            recentFilesRecyclerView.adapter = fileAttachmentsAdapter
            fileManagerImageView.setImageDrawable(style.fileManagerIcon)
            recentFilesTextView.text = style.recentFilesText
            style.recentFilesTextStyle.apply(recentFilesTextView)
            fileManagerImageView.setOnClickListener {
                activityResultLauncher?.launch(Unit)
            }
        }
    }

    private fun checkPermissions() {
        if (!permissionChecker.isGrantedStoragePermissions(requireContext())) {
            permissionChecker.checkStoragePermissions(
                binding.root,
                onPermissionDenied = ::onPermissionDenied,
                onPermissionGranted = ::onPermissionGranted
            )
            return
        }
        onPermissionGranted()
    }

    private fun setupResultListener() {
        activityResultLauncher = activity?.activityResultRegistry
            ?.register(LauncherRequestsKeys.SELECT_FILES, SelectFilesContract()) {
                lifecycleScope.launch(DispatcherProvider.Main) {
                    val attachments = withContext(DispatcherProvider.IO) {
                        storageHelper.getAttachmentsFromUriList(requireContext(), it)
                    }
                    val filteredAttachments = attachmentFilter.filterAttachments(attachments)

                    if (filteredAttachments.size < attachments.size) {
                        Toast.makeText(
                            context,
                            getString(R.string.stream_ui_message_input_file_not_supported),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    attachmentsPickerTabListener?.onSelectedAttachmentsChanged(
                        filteredAttachments,
                        AttachmentSource.FILE
                    )
                    attachmentsPickerTabListener?.onSelectedAttachmentsSubmitted()
                }
            }
    }

    /**
     * Sets the listener invoked when attachments are selected in the attachment tab.
     */
    fun setAttachmentsPickerTabListener(attachmentsPickerTabListener: AttachmentsPickerTabListener) {
        this.attachmentsPickerTabListener = attachmentsPickerTabListener
    }

    private fun onPermissionGranted() {
        binding.grantPermissionsInclude.grantPermissionsContainer.isVisible = false
        populateAttachments()
    }

    private fun onPermissionDenied() {
        binding.grantPermissionsInclude.grantPermissionsContainer.isVisible = true
    }

    private fun populateAttachments() {
        lifecycleScope.launch(DispatcherProvider.Main) {
            binding.progressBar.isVisible = true

            val attachments = withContext(DispatcherProvider.IO) {
                storageHelper.getFileAttachments(requireContext())
            }
            val filteredAttachments = attachmentFilter.filterAttachments(attachments)

            if (filteredAttachments.isEmpty()) {
                style.fileAttachmentEmptyStateTextStyle.apply(binding.emptyPlaceholderTextView)
                binding.emptyPlaceholderTextView.text = style.fileAttachmentEmptyStateText
                binding.emptyPlaceholderTextView.isVisible = true
            } else {
                fileAttachmentsAdapter.setAttachments(filteredAttachments)
            }
            binding.progressBar.isVisible = false
        }
    }

    private object LauncherRequestsKeys {
        const val SELECT_FILES = "select_files_request_key"
    }

    companion object {
        /**
         * Creates a new instance of [FileAttachmentFragment].
         *
         * @param style The style for the attachment picker dialog.
         * @return A new instance of the Fragment.
         */
        fun newInstance(style: MessageInputViewStyle): FileAttachmentFragment {
            return FileAttachmentFragment().apply {
                setStyle(style)
            }
        }
    }
}
