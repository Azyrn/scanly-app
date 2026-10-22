@file:OptIn(ExperimentalMaterial3Api::class)

package com.skeler.scanely.docchat.presentation.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeler.scanely.R
import com.skeler.scanely.docchat.data.AgentLog
import com.skeler.scanely.docchat.data.AiTextBody
import com.skeler.scanely.docchat.data.AskAnswer
import com.skeler.scanely.docchat.data.ChatEntity
import com.skeler.scanely.docchat.data.ChatMessage
import com.skeler.scanely.docchat.data.ErrorBody
import com.skeler.scanely.docchat.data.FieldsBody
import com.skeler.scanely.docchat.data.FileBody
import com.skeler.scanely.docchat.data.MessageBody
import com.skeler.scanely.docchat.data.Role
import com.skeler.scanely.docchat.data.WorkingBody
import com.skeler.scanely.docchat.presentation.DocChatEvent
import com.skeler.scanely.docchat.presentation.DocChatUiState
import com.skeler.scanely.docchat.presentation.DocChatViewModel
import com.skeler.scanely.docchat.presentation.DocChatsViewModel
import com.skeler.scanely.navigation.LocalNavigator
import com.skeler.scanely.ui.components.PaywallSheet
import com.skeler.scanely.ui.viewmodel.PaywallViewModel
import kotlinx.coroutines.launch

/** Key the history screen returns the chosen chat under. */
const val OPEN_CHAT_RESULT = "openChat"

/** The chat, with the app's theme but the chat's own quick, spring-free motion ([ChatMotionScheme]). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DocChatScreen(
    viewModel: DocChatViewModel = hiltViewModel(),
    paywallViewModel: PaywallViewModel = hiltViewModel(),
    chatsViewModel: DocChatsViewModel = hiltViewModel()
) {
    // The chat draws a little denser than the rest of the app (layout and text alike), so more of
    // a conversation fits on screen. Only this screen; the phone's own settings are untouched.
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density * CHAT_DENSITY_SCALE, density.fontScale)
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            motionScheme = ChatMotionScheme,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography
        ) {
            DocChatContent(viewModel, paywallViewModel, chatsViewModel)
        }
    }
}

@Composable
private fun DocChatContent(
    viewModel: DocChatViewModel,
    paywallViewModel: PaywallViewModel,
    chatsViewModel: DocChatsViewModel
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val approval by viewModel.approval.collectAsStateWithLifecycle()
    val paywall by paywallViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var paywallReason by remember { mutableStateOf<String?>(null) }
    var paywallFeatureLock by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renamingChat by remember { mutableStateOf<ChatEntity?>(null) }
    var deletingChat by remember { mutableStateOf<ChatEntity?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chats by chatsViewModel.chats.collectAsStateWithLifecycle()
    val query by chatsViewModel.query.collectAsStateWithLifecycle()
    val openPlans = {
        paywallReason = null
        paywallFeatureLock = false
        showPaywall = true
    }
    var attaching by remember { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    // The missing-details sheet: which fields reply it is open for; it opens by itself once per reply.
    var fieldsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var fieldsShown by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val pendingFields = state.messages.lastOrNull()
        ?.takeIf { (it.body as? FieldsBody)?.let { body -> body.submitted == null } == true }?.id
    LaunchedEffect(pendingFields) {
        if (pendingFields != null && pendingFields !in fieldsShown) {
            fieldsShown = fieldsShown + pendingFields
            fieldsFor = pendingFields
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // "Fully drawn" once the chat's own content is on screen: the cold-open time Android reports.
    ReportDrawnWhen { !state.isLoading }

    LifecycleStartEffect(viewModel) {
        viewModel.setForeground(true)
        onStopOrDispose { viewModel.setForeground(false) }
    }

    // A chat chosen in History comes back on this entry's saved state.
    val chosen by navigator.result<String>(OPEN_CHAT_RESULT).collectAsStateWithLifecycle()
    LaunchedEffect(chosen) {
        chosen?.let {
            viewModel.openChat(it)
            navigator.clearResult(OPEN_CHAT_RESULT)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.oneOffEvents.collect { event ->
            when (event) {
                is DocChatEvent.Message ->
                    snackbar.showSnackbar(resources.getString(event.text, *event.args.toTypedArray()))
                is DocChatEvent.Raw -> snackbar.showSnackbar(event.text)
                is DocChatEvent.Upgrade -> {
                    paywallReason = event.reason
                    paywallFeatureLock = event.isFeatureLock
                    showPaywall = true
                }
            }
        }
    }

    val actions = remember(viewModel) {
        object : ChatActions {
            override fun retry(replyId: String) = viewModel.retry(replyId)
            override fun answer(replyId: String, answer: AskAnswer) = viewModel.answerAsk(replyId, answer)
            override fun submitFields(replyId: String, values: Map<String, String>) =
                viewModel.submitFields(replyId, values)
            override fun open(messageId: String, file: FileBody) = viewModel.open(context, messageId, file)
            override fun share(messageId: String, file: FileBody) = viewModel.share(context, messageId, file)
            override fun download(messageId: String, file: FileBody) = viewModel.download(context, messageId, file)
            override fun saveImages(messageId: String, file: FileBody) =
                viewModel.saveImages(context, messageId, file)
            override fun restore(messageId: String, file: FileBody) = viewModel.restore(messageId, file)
            override fun reviseFrom(designId: String) = viewModel.reviseFrom(designId)
            override fun sendChoice(text: String) = viewModel.sendChoice(text)
            override fun openFields(replyId: String) {
                fieldsFor = replyId
            }
            override fun rebuild(fileMessageId: String) = viewModel.rebuild(fileMessageId)
            override suspend fun output(turnId: String, row: String) = viewModel.rowOutput(turnId, row)
            override fun upgrade(reason: String, code: String?) {
                paywallReason = reason
                paywallFeatureLock = code == "format_not_in_plan"
                showPaywall = true
            }
        }
    }

    // Opening the drawer is when the list should be fresh; the cache shows at once.
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) chatsViewModel.refresh() }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ChatDrawer(
                chats = chats,
                query = query,
                onQuery = chatsViewModel::search,
                currentChatId = state.chatId,
                paywall = paywall,
                callbacks = remember(viewModel, chatsViewModel) {
                    DrawerCallbacks(
                        onNewChat = {
                            viewModel.newChat()
                            scope.launch { drawer.close() }
                        },
                        onOpen = {
                            viewModel.openChat(it)
                            scope.launch { drawer.close() }
                        },
                        onRename = { renamingChat = it },
                        onDelete = { deletingChat = it },
                        onPlan = openPlans
                    )
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                ChatTopBar(
                    state = state,
                    onMenu = { scope.launch { drawer.open() } },
                    onRename = { renaming = true },
                    onNewChat = viewModel::newChat
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            // The composer sits over the bottom of the chat; the list ends at its measured top edge,
            // so no message is ever under it.
            var composerHeight by remember { mutableIntStateOf(0) }
            val composerInset = with(LocalDensity.current) { composerHeight.toDp() }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (state.isEmpty) {
                        ChatEmptyState(
                            modifier = Modifier.padding(bottom = composerInset),
                            onExample = {
                                viewModel.useExample(it)
                                composerFocus.requestFocus()
                            }
                        )
                    } else {
                        MessageList(
                            showJump = fieldsFor == null,
                            bottomInset = if (fieldsFor == null) composerInset else 0.dp,
                            content = ListContent(
                                messages = state.messages,
                                active = state.active,
                                revisionId = state.revision?.designId.takeIf { state.hasManyFiles },
                                canRevise = !state.agentAvailable,
                                working = state.workingReplyId != null,
                                thumbnails = state.thumbnails
                            ),
                            actions = actions
                        )
                    }
                }
                if (fieldsFor == null) {
                    ChatComposer(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { composerHeight = it.height },
                        state = state,
                        paywall = paywall,
                        viewModel = viewModel,
                        attaching = attaching,
                        focusRequester = composerFocus,
                        onAttaching = { attaching = it },
                        onSend = {
                            // Every design can finish after the app is closed; asked once per visit.
                            if (!askedForNotifications && needsNotificationPermission(context)) {
                                askedForNotifications = true
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.send()
                        }
                    )
                }
            }
        }
    }

    fieldsFor?.let { replyId ->
        val body = state.messages.firstOrNull { it.id == replyId }?.body as? FieldsBody
        if (body == null || body.submitted != null) {
            fieldsFor = null
        } else {
            FieldsSheet(
                replyId = replyId,
                body = body,
                onSubmit = { values ->
                    fieldsFor = null
                    viewModel.submitFields(replyId, values)
                },
                onDismiss = { fieldsFor = null }
            )
        }
    }

    approval?.let { asked ->
        ApprovalSheet(
            approval = asked,
            onAnswer = { allow -> viewModel.answerApproval(asked.id, allow) },
            onExpired = { viewModel.approvalExpired(asked.id) }
        )
    }

    renamingChat?.let { chat ->
        RenameChatDialog(
            current = chat.title,
            onRename = {
                chatsViewModel.rename(chat.id, it)
                renamingChat = null
            },
            onDismiss = { renamingChat = null }
        )
    }
    deletingChat?.let { chat ->
        DeleteChatDialog(
            chat = chat,
            onDelete = {
                chatsViewModel.delete(chat.id)
                deletingChat = null
            },
            onDismiss = { deletingChat = null }
        )
    }

    if (renaming) {
        RenameChatDialog(
            current = state.title,
            onRename = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false }
        )
    }

    if (showPaywall) {
        PaywallSheet(
            state = paywall,
            reason = designPaywallReason(paywallReason, stringResource(R.string.design_paywall_fallback)),
            isFeatureLock = paywallFeatureLock,
            onDismiss = {
                showPaywall = false
                paywallViewModel.consumeMessage()
            },
            onRedeem = { key -> paywallViewModel.redeem(key) },
            onRestore = { paywallViewModel.restorePurchase() },
            onConfirmEmail = { paywallViewModel.confirmEmail() },
            onRetry = { paywallViewModel.refresh() },
            canWatchAd = paywall.canWatchAd,
            onWatchAd = { activity?.let { paywallViewModel.watchAd(it) } }
        )
    }
}

/**
 * Menu (the drawer), the chat's title — tap it to rename — and a new chat. Every icon is the same
 * rounded outline at 24dp. An empty chat is titled after the assistant.
 */
@Composable
private fun ChatTopBar(state: DocChatUiState, onMenu: () -> Unit, onRename: () -> Unit, onNewChat: () -> Unit) {
    CenterAlignedTopAppBar(
        title = {
            if (state.chatId == null || state.title.isEmpty()) {
                Text(
                    stringResource(R.string.chat_title_empty),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = stringResource(R.string.chat_rename_action), onClick = onRename)
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        painterResource(R.drawable.ic_sym_expand_more),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(
                    painterResource(R.drawable.ic_sym_menu),
                    contentDescription = stringResource(R.string.chat_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(
                    painterResource(R.drawable.ic_sym_edit_square),
                    contentDescription = stringResource(R.string.chat_new),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

/**
 * A reply's agent log as items of the chat's own list, one per row, so a long log costs only the rows
 * on screen. The list is reversed, so they are added bottom first: under a working reply the rows sit
 * above its status line (its own item); a finished reply's rows sit between its "Worked for" line and
 * its words, and only when [open].
 */
private fun LazyListScope.agentLogItems(
    message: ChatMessage,
    open: Boolean,
    actions: ChatActions,
    toggle: (String) -> Unit
) {
    val body = message.body
    val log = body.agentLog() ?: return
    val turnId = body.agentTurnId()
    val working = body is WorkingBody
    if (working || open) {
        val depths = rowDepths(log.rows)
        items(log.rows.asReversed(), key = { "${message.id}/${it.row}" }, contentType = { "log-row" }) { row ->
            AgentRowItem(row, depths[row.row] ?: 0, turnId, actions, Modifier.padding(end = 8.dp))
        }
        if (log.droppedRows > 0) {
            item(
                key = "${message.id}/dropped",
                contentType = "log-dropped"
            ) { DroppedRows(log.droppedRows) }
        }
    }
    if (!working) {
        val steps = (body as? AiTextBody)?.steps ?: (body as ErrorBody).steps
        item(key = "${message.id}/summary", contentType = "log-summary") {
            LogSummaryLine(log, stepDurations(steps, null).sum(), open) { toggle(message.id) }
        }
    }
}

private fun MessageBody.agentLog(): AgentLog? = when (this) {
    is WorkingBody -> log.takeUnless { it.isEmpty }
    is AiTextBody -> log
    is ErrorBody -> log
    else -> null
}

private fun MessageBody.agentTurnId(): String? = when (this) {
    is WorkingBody -> agentTurnId
    is AiTextBody -> agentTurnId
    is ErrorBody -> agentTurnId
    else -> null
}

/**
 * Newest at the bottom: the list is reversed, so the bottom is item 0 and a growing reply grows
 * upwards. It follows new messages only while the reader is at the bottom; scrolled up, it stays
 * put and offers a jump back instead.
 */
@Composable
private fun MessageList(
    content: ListContent,
    actions: ChatActions,
    showJump: Boolean = true,
    bottomInset: Dp = 0.dp
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Finished replies whose log is unfolded.
    var openLogs by rememberSaveable { mutableStateOf(listOf<String>()) }
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < BOTTOM_SLOP_PX
        }
    }
    FollowNewMessages(listState, content.messages, atBottom)
    val jump = showJump && !atBottom
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // The list ends where the composer starts, and above the jump button while it shows, so
            // neither ever covers a message (the composer was see-through over the chat, 2026-10-16).
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset + if (jump) JUMP_STRIP else 0.dp)
        ) {
            val newestId = content.messages.lastOrNull()?.id
            content.messages.asReversed().forEach { message ->
                item(key = message.id, contentType = message.contentType()) {
                    val designId = (message.body as? FileBody)?.designId
                    MessageRow(
                        message = message,
                        isLive = message.id in content.active,
                        context = ReplyContext(
                            isLatest = message.id == newestId,
                            isRevisionTarget = designId != null && designId == content.revisionId,
                            canRevise = content.canRevise,
                            chatIsWorking = content.working,
                            hasThumbnail = designId in content.thumbnails
                        ),
                        actions = actions,
                        // No placement animation: when the keyboard opens the list shrinks, and animating
                        // every message to its new place first drew them below the list (a blank frame,
                        // then a slide). They now stay attached to the composer; new ones just fade in.
                        modifier = Modifier.animateItem(
                            fadeInSpec = ChatMotion.spec(),
                            placementSpec = null,
                            fadeOutSpec = ChatMotion.spec()
                        )
                    )
                }
                agentLogItems(message, open = message.id in openLogs, actions = actions) { id ->
                    openLogs = if (id in openLogs) openLogs - id else openLogs + id
                }
            }
        }
        // Docked on the composer's top edge, in the strip the list leaves for it.
        AnimatedVisibility(
            visible = jump,
            enter = ChatMotion.fadeIn(),
            exit = ChatMotion.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + JUMP_GAP)
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(JUMP_SIZE)
            ) {
                Icon(
                    painterResource(R.drawable.ic_sym_keyboard_arrow_down),
                    contentDescription = stringResource(R.string.chat_jump_latest)
                )
            }
        }
    }
}

@Composable
private fun FollowNewMessages(listState: LazyListState, messages: List<ChatMessage>, atBottom: Boolean) {
    val newest = messages.lastOrNull()
    // The user's own message always brings the list down; a reply only if they were already there.
    var wasAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) { wasAtBottom = atBottom }
    LaunchedEffect(newest?.id) {
        if (newest == null) return@LaunchedEffect
        if (wasAtBottom || newest.role == Role.USER || messages.getOrNull(messages.lastIndex - 1)?.role == Role.USER) {
            listState.animateScrollToItem(0)
        }
    }
    // The newest reply changing in place (the agent's rows, a working reply becoming its answer) is
    // followed too, but only by a reader already at the bottom.
    LaunchedEffect(newest?.body) {
        if (newest != null && wasAtBottom) listState.scrollToItem(0)
    }
}

/**
 * What the design paywall says: the server's own reason. A fixed "part of Pro" line told a Pro
 * subscriber out of credits that the feature they pay for was Pro-only.
 */
internal fun designPaywallReason(serverMessage: String?, fallback: String): String = serverMessage ?: fallback

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

/**
 * Only what the list draws. Typing in the composer changes the screen's state but none of this,
 * and the instances are the state's own, so the list is skipped on every keystroke.
 */
@Immutable
private data class ListContent(
    val messages: List<ChatMessage>,
    val active: Set<String>,
    /** The file being revised, when the chat has more than one to choose from. */
    val revisionId: String?,
    val canRevise: Boolean,
    val working: Boolean,
    val thumbnails: Set<String>
)

private const val BOTTOM_SLOP_PX = 48

/** The jump-to-latest button (at least a 48 dp tap target), the gap under it, and the strip the list leaves for both. */
private val JUMP_SIZE = 48.dp
private val JUMP_GAP = 4.dp
private val JUMP_STRIP = JUMP_SIZE + JUMP_GAP * 2
private const val CHAT_DENSITY_SCALE = 0.9f
