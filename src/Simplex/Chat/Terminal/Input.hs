{-# LANGUAGE DuplicateRecordFields #-}
{-# LANGUAGE FlexibleContexts #-}
{-# LANGUAGE LambdaCase #-}
{-# LANGUAGE NamedFieldPuns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}

module Simplex.Chat.Terminal.Input where

import Control.Monad.Except
import Control.Monad.Reader
import Data.List (dropWhileEnd)
import qualified Data.Text as T
import Data.Text.Encoding (encodeUtf8)
import Data.Time.Clock (getCurrentTime)
import Simplex.Chat
import Simplex.Chat.Controller
import Simplex.Chat.Styled
import Simplex.Chat.Terminal.Output
import Simplex.Chat.View
import Simplex.Messaging.Util (safeDecodeUtf8)
import System.Exit (exitSuccess)
import System.Terminal hiding (insertChars)
import UnliftIO.STM

getKey :: MonadTerminal m => m (Key, Modifiers)
getKey =
  flush >> awaitEvent >>= \case
    Left Interrupt -> liftIO exitSuccess
    Right (KeyEvent key ms) -> pure (key, ms)
    _ -> getKey

runInputLoop :: ChatTerminal -> ChatController -> IO ()
runInputLoop ct cc = forever $ do
  s <- atomically . readTBQueue $ inputQ cc
  let bs = encodeUtf8 $ T.pack s
      cmd = parseChatCommand bs
  unless (isMessage cmd) $ echo s
  r <- runReaderT (execChatCommand bs) cc
  case r of
    CRChatCmdError _ -> when (isMessage cmd) $ echo s
    _ -> pure ()
  let testV = testView $ config cc
  user <- readTVarIO $ currentUser cc
  ts <- getCurrentTime
  printToTerminal ct $ responseToView user testV ts r
  where
    echo s = printToTerminal ct [plain s]
    isMessage = \case
      Right SendMessage {} -> True
      Right SendFile {} -> True
      Right SendMessageQuote {} -> True
      Right SendGroupMessageQuote {} -> True
      Right SendMessageBroadcast {} -> True
      _ -> False

runTerminalInput :: ChatTerminal -> ChatController -> IO ()
runTerminalInput ct cc = withChatTerm ct $ do
  updateInput ct
  receiveFromTTY cc ct

receiveFromTTY :: MonadTerminal m => ChatController -> ChatTerminal -> m ()
receiveFromTTY ChatController {inputQ, activeTo} ct@ChatTerminal {termSize, termState} =
  forever $ getKey >>= processKey >> withTermLock ct (updateInput ct)
  where
    processKey :: MonadTerminal m => (Key, Modifiers) -> m ()
    processKey = \case
      (EnterKey, _) -> submitInput
      key -> atomically $ do
        ac <- readTVar activeTo
        modifyTVar termState $ updateTermState ac (width termSize) key

    submitInput :: MonadTerminal m => m ()
    submitInput = atomically $ do
      ts <- readTVar termState
      let s = inputString ts
      writeTVar termState $ ts {inputString = "", inputPosition = 0, previousInput = s}
      writeTBQueue inputQ s

updateTermState :: ActiveTo -> Int -> (Key, Modifiers) -> TerminalState -> TerminalState
updateTermState ac tw (key, ms) ts@TerminalState {inputString = s, inputPosition = p} = case key of
  CharKey c
    | ms == mempty || ms == shiftKey -> insertCharsWithContact [c]
    | ms == altKey && c == 'b' -> setPosition prevWordPos
    | ms == altKey && c == 'f' -> setPosition nextWordPos
    | otherwise -> ts
  TabKey -> insertCharsWithContact "    "
  BackspaceKey -> backDeleteChar
  DeleteKey -> deleteChar
  HomeKey -> setPosition 0
  EndKey -> setPosition $ length s
  ArrowKey d -> case d of
    Leftwards -> setPosition leftPos
    Rightwards -> setPosition rightPos
    Upwards
      | ms == mempty && null s -> let s' = upArrowCmd $ previousInput ts in ts' (s', length s')
      | ms == mempty -> let p' = p - tw in if p' > 0 then setPosition p' else ts
      | otherwise -> ts
    Downwards
      | ms == mempty -> let p' = p + tw in if p' <= length s then setPosition p' else ts
      | otherwise -> ts
  _ -> ts
  where
    insertCharsWithContact cs
      | null s && cs /= "@" && cs /= "#" && cs /= "/" && cs /= ">" && cs /= "\\" && cs /= "!" =
        insertChars $ contactPrefix <> cs
      | (s == ">" || s == "\\" || s == "!") && cs == " " =
        insertChars $ cs <> contactPrefix
      | otherwise = insertChars cs
    insertChars = ts' . if p >= length s then append else insert
    append cs = let s' = s <> cs in (s', length s')
    insert cs = let (b, a) = splitAt p s in (b <> cs <> a, p + length cs)
    contactPrefix = case ac of
      ActiveNone -> ""
      ActiveC c -> "@" <> T.unpack c <> " "
      ActiveG g -> "#" <> T.unpack g <> " "
    backDeleteChar
      | p == 0 || null s = ts
      | p >= length s = ts' (init s, length s - 1)
      | otherwise = let (b, a) = splitAt p s in ts' (init b <> a, p - 1)
    deleteChar
      | p >= length s || null s = ts
      | p == 0 = ts' (tail s, 0)
      | otherwise = let (b, a) = splitAt p s in ts' (b <> tail a, p)
    leftPos
      | ms == mempty = max 0 (p - 1)
      | ms == shiftKey = 0
      | ms == ctrlKey = prevWordPos
      | ms == altKey = prevWordPos
      | otherwise = p
    rightPos
      | ms == mempty = min (length s) (p + 1)
      | ms == shiftKey = length s
      | ms == ctrlKey = nextWordPos
      | ms == altKey = nextWordPos
      | otherwise = p
    upArrowCmd inp = case parseChatCommand . encodeUtf8 $ T.pack inp of
      Left _ -> inp
      Right cmd -> case cmd of
        SendMessage {} -> "! " <> inp
        SendMessageQuote {contactName, message} -> T.unpack $ "! @" <> contactName <> " " <> safeDecodeUtf8 message
        SendGroupMessageQuote {groupName, message} -> T.unpack $ "! #" <> groupName <> " " <> safeDecodeUtf8 message
        _ -> inp
    setPosition p' = ts' (s, p')
    prevWordPos
      | p == 0 || null s = p
      | otherwise =
        let before = take p s
            beforeWord = dropWhileEnd (/= ' ') $ dropWhileEnd (== ' ') before
         in max 0 $ p - length before + length beforeWord
    nextWordPos
      | p >= length s || null s = p
      | otherwise =
        let after = drop p s
            afterWord = dropWhile (/= ' ') $ dropWhile (== ' ') after
         in min (length s) $ p + length after - length afterWord
    ts' (s', p') = ts {inputString = s', inputPosition = p'}
