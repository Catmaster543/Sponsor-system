# Sponsor system

A server-side mod for Minecraft neoforge designed to prevent mess and chaos on servers.
Now you might be asking: *Well how can a mod prevent players from misbehaving?* And you're right, it does not do so directly. Instead it helps **you**, as the server admin to make it very easy to estabilish a fair system that everyone will have in their own interest to follow.
This is done through a so called "sponsor system", let me tell you about how it works.

# Invites
This is the core of the mod; every single player that wants to join the server needs to be invited by someone else that is already on the server.
Each player gets a limited amount of invites/sponsorships (more on them below), which also makes invites pretty valuable. 
By inviting a new player, the inviter will now hold responsibility for that players actions. Meaning if the new player breaks the server rules, the inviter will also suffer the consequences.
That is the whole idea of this system. Quite simple, ain't it?
Any players can invite another with the use of **/invite \*player\***
Well that would be far too simple, let me show you some more stuff this mod contains.

# Sponsorships
Every player can sponsor any other player on the server. This works just about the same as invites; if the player you sponsor breaks the rules, you will suffer the consequences as well.
Sponsorships exist for only one reason - to help build a community on the server; people that trust each other.

# Un-inviting/unsponsoring
If you ever get into an argument with the player you sponsor, or invited, you can stop sponsoring them using **/uninvite**, or **/unsponsor**. The moment you stop sponsoring them you are freed from the consequences of their actions.
If a player loses all of their sponsors they will be in a bit of trouble; they now have 30 mins to find a new sponsor, otherwise they are un-whitelisted and lose access to the server until re-invited by someone.

# Admin featues
The first player that joins the server does not need to be invited, as it is expected to be the admin. That first player also becomes the "root" of the invite tree. Every single player that ever joins needs to be connected to the root player in some way. They do not need to be invited by the root player directly; it can be a player that was invited by the root player, but there still needs to be some way on the tree they will remain connected to the root player.
To view who invited who, who is sponsoring who, or who is sponsored by who, you, as an admin can use the **/invitetree \*player_X\***, which will reveal all of this info. All players that will pop-up can be clicked, which will allow you to easily click through the invite tree.
Non-admin players get a variant of this command too, the **/mysponsors** command. It works kinda similar, just only shows the player that used the command and cannot click through the invite tree.
