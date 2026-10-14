package cmd

import "testing"

func TestAddCommandHasOptionalYesFlag(t *testing.T) {
	flag := addCmd.PersistentFlags().Lookup("yes")
	if flag == nil {
		t.Fatal("параметр --yes отсутствует")
	}
	if flag.Value.Type() != "bool" || flag.DefValue != "false" {
		t.Fatalf("параметр --yes имеет тип %q и значение по умолчанию %q", flag.Value.Type(), flag.DefValue)
	}
}
