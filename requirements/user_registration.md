The first feature we are going to work on is the user registration.

We are going to create a very simple registration form that would enable users to register passing email and 
password. We won't do any verification of the reachability of the email beyond the fact that the provided emails 
MUST be valid emails. The passwords will be at least 8 characters long with uppercase, lowercase, numbers and 
special characters.

We will use a DB, and for the time being we will use H2. Passwords will be stored encrypted.

It's really important to create proper structured code separating entity code, access code, and creating a well define service interface.

We want to have 100% code coverate using hegel-java.