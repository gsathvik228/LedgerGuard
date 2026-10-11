export type Session = { token: string; email: string; role: 'CUSTOMER' | 'ADMIN'; fullName: string };
export type Account = { id: number; accountNumber: string; accountType: string; balance: string; currency: string; status: string };
export type Transaction = { id: number; type: string; amount: string; currency: string; status: string; riskLevel: string; riskScore: number; description: string | null; createdAt: string; senderAccountId: number | null; recipientAccountId: number | null; confirmationExpiresAt: string | null; resolvedAt: string | null; outcomeReason: string | null; canConfirm: boolean; canCancel: boolean; reviewExpiresAt: string | null };
export type Assessment = { riskScore: number; riskLevel: string; decision: string; createdAt: string; rules: { ruleName: string; severity: string; reason: string; scoreContribution: number }[] };
export type Analysis = Assessment & { confirmation: Assessment | null; review: Assessment | null };
export type Page<T> = { content: T[]; totalElements: number; totalPages: number; number: number; size: number };
export type Dashboard = { totalTransactions: number; completedVolumeByCurrency: Record<string,string>; byStatus: Record<string,number>; byType: Record<string,number>; byRisk: Record<string,number> };
