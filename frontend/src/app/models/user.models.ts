/** Mirrors UserSummary.java / UserListResponse.java from user-service. */

export interface UserSummary {
  id: number;
  username: string;
  firstName: string;
  lastName: string | null;
}

export interface UserListResponse {
  users: UserSummary[];
  message: string;
}
