-- Lot 49 : un serveur de la team n'est plus protégé contre la suppression à la main
-- (son propriétaire peut le supprimer) ; il reste à l'abri de la suppression pour
-- inactivité. Seul le lobby IMG reste protégé : l'étape velocity du kit le reprotège.
UPDATE servers SET is_protected = FALSE WHERE is_team;
