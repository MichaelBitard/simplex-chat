package chat.simplex.app.views.chat.item

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.simplex.app.R
import chat.simplex.app.model.*
import chat.simplex.app.ui.theme.HighOrLowlight
import kotlinx.datetime.Clock

@Composable
fun CIMetaView(chatItem: ChatItem, metaColor: Color = HighOrLowlight) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (!chatItem.isDeletedContent) {
      if (chatItem.meta.itemEdited) {
        Icon(
          Icons.Filled.Edit,
          modifier = Modifier.height(12.dp).padding(end = 1.dp),
          contentDescription = stringResource(R.string.icon_descr_edited),
          tint = metaColor,
        )
      }
      CIStatusView(chatItem.meta.itemStatus, metaColor)
    }
    Text(
      chatItem.timestampText,
      color = metaColor,
      fontSize = 14.sp,
      modifier = Modifier.padding(start = 3.dp)
    )
  }
}


@Composable
fun CIStatusView(status: CIStatus, metaColor: Color = HighOrLowlight) {
  when (status) {
    is CIStatus.SndSent -> {
      Icon(Icons.Filled.Check, stringResource(R.string.icon_descr_sent_msg_status_sent), Modifier.height(12.dp), tint = metaColor)
    }
    is CIStatus.SndErrorAuth -> {
      Icon(Icons.Filled.Close,  stringResource(R.string.icon_descr_sent_msg_status_unauthorized_send), Modifier.height(12.dp), tint = Color.Red)
    }
    is CIStatus.SndError -> {
      Icon(Icons.Filled.WarningAmber, stringResource(R.string.icon_descr_sent_msg_status_send_failed), Modifier.height(12.dp), tint = Color.Yellow)
    }
    is CIStatus.RcvNew -> {
      Icon(Icons.Filled.Circle, stringResource(R.string.icon_descr_received_msg_status_unread), Modifier.height(12.dp), tint = MaterialTheme.colors.primary)
    }
    else -> {}
  }
}

@Preview
@Composable
fun PreviewCIMetaView() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello"
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewUnread() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
      status = CIStatus.RcvNew()
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendFailed() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
      status = CIStatus.SndError(AgentErrorType.CMD(CommandErrorType.SYNTAX()))
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendNoAuth() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello", status = CIStatus.SndErrorAuth()
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewSendSent() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello", status = CIStatus.SndSent()
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEdited() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
      itemEdited = true
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEditedUnread() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectRcv(), Clock.System.now(), "hello",
      itemEdited = true,
      status=CIStatus.RcvNew()
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewEditedSent() {
  CIMetaView(
    chatItem = ChatItem.getSampleData(
      1, CIDirection.DirectSnd(), Clock.System.now(), "hello",
      itemEdited = true,
      status=CIStatus.SndSent()
    )
  )
}

@Preview
@Composable
fun PreviewCIMetaViewDeletedContent() {
  CIMetaView(
    chatItem = ChatItem.getDeletedContentSampleData()
  )
}
