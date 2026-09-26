import { createContext, useContext, type ReactNode } from 'react';
import type { ChatAppKey } from './chatAppDefinitions';

export type OpenChatApp = (key: ChatAppKey) => void;

const ChatAppsContext = createContext<OpenChatApp | null>(null);

export function ChatAppsProvider({
  open,
  children,
}: {
  open: OpenChatApp;
  children: ReactNode;
}) {
  return <ChatAppsContext.Provider value={open}>{children}</ChatAppsContext.Provider>;
}

export function useChatAppOpener(): OpenChatApp | null {
  return useContext(ChatAppsContext);
}
