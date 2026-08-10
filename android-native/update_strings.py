import xml.etree.ElementTree as ET

def update_xml(file_path, new_strings, to_remove):
    tree = ET.parse(file_path)
    root = tree.getroot()
    
    # Remove unused strings
    for string in root.findall('string'):
        if string.get('name') in to_remove:
            root.remove(string)
            
    # Fix typo in detail_watch_episode
    for string in root.findall('string'):
        if string.get('name') == 'detail_watch_episode':
            if 'T%1$dE1' in string.text:
                string.text = string.text.replace('T%1$dE1', 'T%1$dE%2$d')
            elif 'S%1$dE1' in string.text:
                string.text = string.text.replace('S%1$dE1', 'S%1$dE%2$d')

    # Add new strings
    for name, value in new_strings.items():
        # Check if exists
        exists = False
        for string in root.findall('string'):
            if string.get('name') == name:
                exists = True
                string.text = value
                break
        if not exists:
            elem = ET.SubElement(root, 'string', {'name': name})
            elem.text = value
            
    ET.indent(tree, space="    ", level=0)
    tree.write(file_path, encoding='utf-8', xml_declaration=True)

pt_strings = {
    'home_yes': 'Sim',
    'home_no': 'Não',
    'home_rename_list': 'Renomear lista',
    'home_save': 'Salvar',
    'home_cancel': 'Cancelar',
    'home_delete_list_question': 'Excluir lista?',
    'home_delete': 'Excluir',
    'search_searching': 'Buscando...',
    'search_no_results': 'Nenhum resultado encontrado.',
    'loading_opening': 'Abrindo…',
    'loading_fetching': 'Carregando ficha…',
    'login_error_google': 'Não consegui entrar com o Google.',
    'login_error_mdblist_link': 'Não consegui vincular a MDBList. Confira a chave e tente de novo.',
    'login_error_mdblist_required': 'Não foi possível prosseguir sem MDBList.',
    'addons_mdblist_linked': 'MDBList vinculada. Ative os addons de listas somente se quiser usá-los no Stremio.',
    'addons_mdblist_link_error': 'Não consegui vincular a MDBList. %1$s',
    'addons_mdblist_removed': '%1$d addon(s) de listas MDBList removido(s) do Open Stream.',
    'addons_mdblist_disabled': 'Uso das listas MDBList como addons desligado.',
    'addons_mdblist_enabled': 'Listas MDBList ligadas: %1$d addon(s) adicionado(s).%2$s',
    'addons_mdblist_updated': '%1$d lista(s) MDBList atualizada(s) no Open Stream.%2$s'
}

en_strings = {
    'home_yes': 'Yes',
    'home_no': 'No',
    'home_rename_list': 'Rename list',
    'home_save': 'Save',
    'home_cancel': 'Cancel',
    'home_delete_list_question': 'Delete list?',
    'home_delete': 'Delete',
    'search_searching': 'Searching...',
    'search_no_results': 'No results found.',
    'loading_opening': 'Opening…',
    'loading_fetching': 'Loading details…',
    'login_error_google': 'Could not sign in with Google.',
    'login_error_mdblist_link': 'Could not link MDBList. Check the key and try again.',
    'login_error_mdblist_required': 'Could not proceed without MDBList.',
    'addons_mdblist_linked': 'MDBList linked. Enable lists addons only if you want to use them in Stremio.',
    'addons_mdblist_link_error': 'Could not link MDBList. %1$s',
    'addons_mdblist_removed': '%1$d MDBList addon(s) removed from Open Stream.',
    'addons_mdblist_disabled': 'Using MDBList lists as addons disabled.',
    'addons_mdblist_enabled': 'MDBList lists enabled: %1$d addon(s) added.%2$s',
    'addons_mdblist_updated': '%1$d MDBList list(s) updated in Open Stream.%2$s'
}

to_remove = ['lang_es', 'lang_fr', 'lang_ja', 'detail_crew_title', 'addons_unconfigured_url_only']

base_path = '/home/mestrey/Documentos/GitHub/open_Stremio/open Stremio/android-native/app/src/main/res/'
update_xml(base_path + 'values/strings.xml', pt_strings, to_remove)
update_xml(base_path + 'values-en/strings.xml', en_strings, to_remove)

print("Strings updated successfully!")
