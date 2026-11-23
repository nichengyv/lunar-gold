/**
 * 插件中心类型定义，与后端 com.capsule.agentkey.plugin 包对齐。
 */

export type PluginType = 'MCP' | 'PROMPT_TEMPLATE' | 'MODEL_PRESET';

export interface CredentialField {
  name: string;
  label: string;
  description?: string;
  required: boolean;
  secret: boolean;
  placeholder?: string;
}

export interface PluginManifest {
  id: string;
  name: string;
  description: string;
  icon?: string;
  category?: string;
  type?: PluginType;
  transport: string;
  command: string;
  args?: string[];
  envTemplate?: Record<string, string>;
  runtime?: string[];
  toolCount?: number;
  requiredPermissions?: string[];
  credentials?: CredentialField[];
  homepageUrl?: string;
  version: string;
}

export interface Catalog {
  version: string;
  plugins: PluginManifest[];
}

export interface PluginInstallRequest {
  pluginId: string;
  credentials: Record<string, string>;
}

/** 运行时预检结果（对应后端 RuntimeDetector.RuntimeCheckResult） */
export interface RuntimeCheckResult {
  spec: string;
  name: string;
  satisfied: boolean;
  detectedVersion: string | null;
  message: string;
}
