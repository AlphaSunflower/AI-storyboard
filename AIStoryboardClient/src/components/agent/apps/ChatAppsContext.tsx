import { createContext, useContext, type ReactNode } from 'react';
import type { ChatAppKey, ChatAppOpenOptions } from './chatAppDefinitions';

export type OpenChatApp = (key: ChatAppKey, options?: ChatAppOpenOptions) => void;

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
