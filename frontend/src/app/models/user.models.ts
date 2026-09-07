/** Mirrors UserSummary.java / UserListResponse.java from user-service. */

/**
 * `userType` distinguishes the two assistants from real people, so the list
 * can pin them to the top and colour them apart. With two bots answering,
 * a flat alphabetical list makes the one you want hard to find and the two
 * of them hard to tell apart mid-demo.
 */
export type UserType = 'USER' | 'BOT' | 'BOT_TOOL';

export interface UserSummary {
  id: number;
  username: string;
  firstName: string;
  lastName: string | null;
  userType: UserType;
}

export interface UserListResponse {
  users: UserSummary[];
  message: string;
}
