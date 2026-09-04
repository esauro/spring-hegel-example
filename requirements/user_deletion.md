I want to add a new feature that would consist in deleting of the current user. Only logged in users can delete themselves, through the new button and api endpoint specific for that. As before I'd like to reuse existing services if possible. A user that is deleted is also logged out meaning the sessions will be destroyed.

If the user is not active, meaning there's an active session for them, it shouldn't be possible to delete them

As part of this work I'd like to do an full integration property test:
1. A new user is created
2. The user can log in
3. The user can log out
4. The user can log in
5. The user can delete its own user
6. A new user with the same email should be able to be created

To avoid test interference the test will finish with deleting the user.