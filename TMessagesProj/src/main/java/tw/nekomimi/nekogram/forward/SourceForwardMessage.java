package tw.nekomimi.nekogram.forward;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.MessageSuggestionParams;

import java.util.ArrayList;
import java.util.HashMap;

/** A separate outgoing snapshot: never edit the original message or its entities. */
public class SourceForwardMessage extends MessageObject {
    private final MessageObject original;
    private SourceForwardMessage(MessageObject original, TLRPC.Message copy) {
        super(original.currentAccount, copy, true, false);
        this.original = original;
    }

    public static String getSourceLink(MessageObject message) {
        if (message == null || message.messageOwner == null || message.getId() <= 0 || message.getDialogId() >= 0) {
            return null;
        }
        final MessagesController controller = MessagesController.getInstance(message.currentAccount);
        final TLRPC.Chat chat = controller.getChat(-message.getDialogId());
        if (chat == null || controller.isPeerNoForwards(message.getDialogId()) || message.messageOwner.noforwards) {
            return null;
        }
        final String username = ChatObject.getPublicUsername(chat);
        if (username != null && !username.isEmpty()) {
            return "https://t.me/" + username + "/" + message.getId();
        }
        return ChatObject.isChannel(chat) ? "https://t.me/c/" + chat.id + "/" + message.getId() : null;
    }

    public static boolean canPrepare(MessageObject message) {
        if (message == null || message.messageOwner == null || message.messageOwner.rich_message != null) {
            return false;
        }
        final TLRPC.MessageMedia media = message.messageOwner.media;
        return (media == null || media instanceof TLRPC.TL_messageMediaEmpty || media instanceof TLRPC.TL_messageMediaWebPage
                || media instanceof TLRPC.TL_messageMediaPhoto && media.photo instanceof TLRPC.TL_photo
                || media instanceof TLRPC.TL_messageMediaDocument && media.document instanceof TLRPC.TL_document)
                && !message.isAnyKindOfSticker() && !message.isVoice() && !message.isRoundVideo()
                && message.messageOwner.action == null && getSourceLink(message) != null;
    }

    public static ArrayList<MessageObject> prepare(ArrayList<MessageObject> originals) {
        final ArrayList<MessageObject> result = new ArrayList<>();
        final ArrayList<MessageObject> overflow = new ArrayList<>();
        for (MessageObject original : originals) {
            if (original instanceof SourceForwardMessage || !canPrepare(original)) {
                result.add(original);
                continue;
            }
            final String sourceLink = getSourceLink(original);
            final TLRPC.Message copy = new TLRPC.TL_message();
            copy.id = original.getId();
            copy.dialog_id = original.getDialogId();
            copy.peer_id = original.messageOwner.peer_id;
            copy.from_id = original.messageOwner.from_id;
            copy.date = original.messageOwner.date;
            copy.media = original.messageOwner.media;
            copy.grouped_id = original.messageOwner.grouped_id;
            copy.attachPath = original.messageOwner.attachPath;
            copy.message = (original.messageOwner.message == null ? "" : original.messageOwner.message)
                    + "\n\n" + LocaleController.getString(R.string.ForwardSourcePrefix) + " " + sourceLink + " ";
            copy.entities = sliceEntities(original.messageOwner.entities, 0, copy.message.length());
            final TLRPC.TL_messageEntityUrl link = new TLRPC.TL_messageEntityUrl();
            link.offset = copy.message.length() - sourceLink.length() - 1;
            link.length = sourceLink.length();
            copy.entities.add(link);
            copy.flags = 128 | (copy.media == null ? 0 : 512) | (copy.from_id == null ? 0 : 256);
            final MessagesController controller = MessagesController.getInstance(original.currentAccount);
            final boolean media = copy.media instanceof TLRPC.TL_messageMediaPhoto || copy.media instanceof TLRPC.TL_messageMediaDocument;
            if (media && copy.message.length() <= controller.getCaptionMaxLengthLimit()) {
                result.add(new SourceForwardMessage(original, copy));
            } else {
                // Put oversized captions after the whole album, keeping the album intact.
                final String text = copy.message;
                final ArrayList<TLRPC.MessageEntity> entities = copy.entities;
                if (media) {
                    copy.message = "";
                    copy.entities = new ArrayList<>();
                    result.add(new SourceForwardMessage(original, copy));
                }
                final ArrayList<MessageObject> destination = media ? overflow : result;
                for (int start = 0; start < text.length();) {
                    final int end = partEnd(text, entities, start, Math.max(2, controller.maxMessageLength));
                    final TLRPC.Message part = new TLRPC.TL_message();
                    part.id = !media && start == 0 ? copy.id : -(result.size() + overflow.size() + 1);
                    part.dialog_id = copy.dialog_id;
                    part.peer_id = copy.peer_id;
                    part.from_id = copy.from_id;
                    part.date = copy.date;
                    part.message = text.substring(start, end);
                    part.entities = sliceEntities(entities, start, end);
                    part.flags = 128 | (part.from_id == null ? 0 : 256);
                    destination.add(new SourceForwardMessage(original, part));
                    start = end;
                }
            }
        }
        result.addAll(overflow);
        return result;
    }

    public void send(SendMessagesHelper helper, long peer, MessageObject replyToTopMsg, boolean notify,
                     int scheduleDate, int scheduleRepeatPeriod, long payStars, long monoForumPeerId,
                     MessageSuggestionParams suggestionParams, HashMap<String, String> groupParams) {
        if (messageOwner.media instanceof TLRPC.TL_messageMediaPhoto || messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
            final String caption = messageOwner.message;
            final ArrayList<TLRPC.MessageEntity> entities = sliceEntities(messageOwner.entities, 0, caption.length());
            final SendMessagesHelper.SendMessageParams params;
            if (messageOwner.media.photo instanceof TLRPC.TL_photo) {
                params = SendMessagesHelper.SendMessageParams.of((TLRPC.TL_photo) messageOwner.media.photo, null,
                        peer, null, replyToTopMsg, caption, entities, null, groupParams, notify, scheduleDate,
                        scheduleRepeatPeriod, messageOwner.media.ttl_seconds, original, false, messageOwner.media.spoiler);
            } else {
                params = SendMessagesHelper.SendMessageParams.of((TLRPC.TL_document) messageOwner.media.document,
                        null, original.messageOwner.attachPath, peer, null, replyToTopMsg, caption, entities,
                        null, groupParams, notify, scheduleDate, scheduleRepeatPeriod, messageOwner.media.ttl_seconds,
                        original, null, false, messageOwner.media.spoiler);
            }
            params.payStars = payStars;
            params.monoForumPeer = monoForumPeerId;
            params.suggestionParams = suggestionParams;
            helper.sendMessage(params);
            return;
        }
        sendText(helper, peer, replyToTopMsg, notify, scheduleDate, scheduleRepeatPeriod, payStars, monoForumPeerId, suggestionParams);
    }

    private void sendText(SendMessagesHelper helper, long peer, MessageObject replyToTopMsg, boolean notify,
                          int scheduleDate, int scheduleRepeatPeriod, long payStars, long monoForumPeerId,
                          MessageSuggestionParams suggestionParams) {
        final String text = messageOwner.message;
        final int limit = Math.max(2, MessagesController.getInstance(currentAccount).maxMessageLength);
        for (int start = 0; start < text.length();) {
            final int end = partEnd(text, messageOwner.entities, start, limit);
            final ArrayList<TLRPC.MessageEntity> entities = sliceEntities(messageOwner.entities, start, end);
            final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    text.substring(start, end), peer, null, replyToTopMsg, null, false, entities, null, null,
                    notify, scheduleDate, scheduleRepeatPeriod, null, false);
            params.payStars = payStars;
            params.monoForumPeer = monoForumPeerId;
            params.suggestionParams = suggestionParams;
            helper.sendMessage(params);
            start = end;
        }
    }

    private static int partEnd(String text, ArrayList<TLRPC.MessageEntity> entities, int start, int limit) {
        int end = Math.min(start + limit, text.length());
        for (TLRPC.MessageEntity entity : entities) {
            if (entity instanceof TLRPC.TL_messageEntityUrl && entity.offset > start
                    && entity.offset < end && entity.offset + entity.length > end && entity.length <= limit) {
                end = entity.offset;
            }
        }
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return end;
    }

    static ArrayList<TLRPC.MessageEntity> sliceEntities(ArrayList<TLRPC.MessageEntity> entities, int start, int end) {
        final ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        for (TLRPC.MessageEntity entity : entities) {
            final int left = Math.max(start, entity.offset);
            final int right = Math.min(end, entity.offset + entity.length);
            if (left >= right) {
                continue;
            }
            final SerializedData output = new SerializedData(entity.getObjectSize());
            entity.serializeToStream(output);
            final SerializedData input = new SerializedData(output.toByteArray());
            final TLRPC.MessageEntity copy = TLRPC.MessageEntity.TLdeserialize(input, input.readInt32(true), true);
            input.cleanup();
            output.cleanup();
            copy.offset = left - start;
            copy.length = right - left;
            result.add(copy);
        }
        return result;
    }
}
